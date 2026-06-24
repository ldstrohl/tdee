/**
 * TDEE NL food-parse proxy.
 *
 * POST /parse  { "text": "two eggs and a bowl of oatmeal" }
 *   -> { "items": [ ParsedFoodItem, ... ] }
 *
 * ParsedFoodItem is the exact shape the Android app's WorkerFoodParser consumes,
 * mirroring com.tdee.app.data.ParsedFoodItem:
 *   { name, displayQuantity, unit, grams, kcal, proteinG, fatG, carbG, needsConfirmation }
 *
 * Google Gemini (with thinking enabled) decomposes the free text into discrete
 * food items with estimated quantities and macros, via structured-output JSON.
 * Its holistic estimates are returned as-is — the user confirms/adjusts each item
 * on the app's confirmation screen (needsConfirmation is always true).
 *
 * (An earlier version overrode these with USDA FoodData Central top-1 matches
 * scaled by the estimated grams; that produced worse numbers for free-text meals
 * — e.g. "coffee" matching a caloric product — so it was removed.)
 */

interface Env {
  GEMINI_API_KEY: string;
  GEMINI_MODEL?: string; // defaults to DEFAULT_MODEL (set as a [vars] entry in wrangler.toml)
  PROXY_SHARED_SECRET?: string;
}

const DEFAULT_MODEL = "gemini-2.5-flash";

const SYSTEM_PROMPT = `You convert a free-text meal description into structured food items.

Rules:
- Split the description into discrete foods. Do not split a single named dish (e.g. "chicken sandwich") into its components.
- Estimate a reasonable quantity, unit, and total grams for each item based on the description and typical serving sizes.
- Estimate realistic calories and macronutrients for the whole quantity stated (not per 100g). Use your nutrition knowledge; a plain black coffee is ~5 kcal, a pat of butter ~35 kcal, etc.
- "query" is a short, generic search phrase for the food (e.g. "scrambled eggs", "cooked oatmeal") — no quantities, no brand names unless the user named one.
- If the text contains no food, return an empty items array.
- All numbers must be non-negative. Round to whole numbers.`;

// Gemini responseSchema is an OpenAPI 3.0 subset: uppercase type enums, no
// additionalProperties. propertyOrdering keeps the model's output stable.
const ITEM_PROPS = [
  "name",
  "query",
  "displayQuantity",
  "unit",
  "grams",
  "kcal",
  "proteinG",
  "fatG",
  "carbG",
] as const;

const RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    items: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          name: { type: "STRING", description: "Display name, e.g. 'Scrambled eggs'" },
          query: { type: "STRING", description: "Generic food search phrase" },
          displayQuantity: { type: "NUMBER", description: "Numeric quantity, e.g. 2" },
          unit: { type: "STRING", description: "Unit, e.g. 'egg', 'cup', 'g'" },
          grams: { type: "NUMBER", description: "Estimated total mass in grams" },
          kcal: { type: "NUMBER", description: "Estimated total calories" },
          proteinG: { type: "NUMBER", description: "Estimated total protein in grams" },
          fatG: { type: "NUMBER", description: "Estimated total fat in grams" },
          carbG: { type: "NUMBER", description: "Estimated total carbohydrate in grams" },
        },
        required: [...ITEM_PROPS],
        propertyOrdering: [...ITEM_PROPS],
      },
    },
  },
  required: ["items"],
};

interface ModelItem {
  name: string;
  query: string;
  displayQuantity: number;
  unit: string;
  grams: number;
  kcal: number;
  proteinG: number;
  fatG: number;
  carbG: number;
}

interface ParsedFoodItem {
  name: string;
  displayQuantity: number;
  unit: string;
  grams: number;
  kcal: number;
  proteinG: number;
  fatG: number;
  carbG: number;
  needsConfirmation: boolean;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/parse") {
      return json({ error: "not_found" }, 404);
    }

    if (env.PROXY_SHARED_SECRET) {
      const auth = request.headers.get("Authorization") ?? "";
      if (auth !== `Bearer ${env.PROXY_SHARED_SECRET}`) {
        return json({ error: "unauthorized" }, 401);
      }
    }

    let text: string;
    try {
      const body = (await request.json()) as { text?: unknown };
      if (typeof body.text !== "string" || body.text.trim() === "") {
        return json({ error: "missing_text" }, 400);
      }
      text = body.text.trim();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    let modelItems: ModelItem[];
    try {
      modelItems = await parseWithGemini(env, text);
    } catch (e) {
      return json({ error: "parse_failed", detail: String(e) }, 502);
    }

    const items: ParsedFoodItem[] = modelItems.map((m) => ({
      name: m.name,
      displayQuantity: m.displayQuantity,
      unit: m.unit,
      grams: m.grams,
      kcal: m.kcal,
      proteinG: m.proteinG,
      fatG: m.fatG,
      carbG: m.carbG,
      needsConfirmation: true,
    }));

    return json({ items });
  },
};

async function parseWithGemini(env: Env, text: string): Promise<ModelItem[]> {
  const model = env.GEMINI_MODEL || DEFAULT_MODEL;
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;

  const requestBody = JSON.stringify({
    systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
    contents: [{ role: "user", parts: [{ text }] }],
    generationConfig: {
      temperature: 0,
      maxOutputTokens: 4096,
      responseMimeType: "application/json",
      responseSchema: RESPONSE_SCHEMA,
      // Force dynamic thinking on (-1). gemini-2.5-flash defaults to this, but
      // gemini-2.5-flash-lite defaults thinking OFF — and thinking gives
      // noticeably better macro estimates. Set to 0 to disable for max speed/cost.
      thinkingConfig: { thinkingBudget: -1 },
    },
  });

  // Gemini Flash returns transient 503 (UNAVAILABLE) under load; retry briefly.
  let res: Response | null = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": env.GEMINI_API_KEY,
      },
      body: requestBody,
    });
    if (res.ok) break;
    if ((res.status === 503 || res.status === 429 || res.status === 500) && attempt < 2) {
      await new Promise((r) => setTimeout(r, 400 * (attempt + 1)));
      continue;
    }
    break;
  }

  if (!res || !res.ok) {
    throw new Error(`gemini ${res?.status}: ${res ? await res.text() : "no response"}`);
  }

  const data = (await res.json()) as {
    candidates?: Array<{
      content?: { parts?: Array<{ text?: string }> };
      finishReason?: string;
    }>;
    promptFeedback?: { blockReason?: string };
  };

  if (data.promptFeedback?.blockReason) {
    throw new Error(`blocked: ${data.promptFeedback.blockReason}`);
  }
  const out = data.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!out) {
    throw new Error(`no content (finishReason=${data.candidates?.[0]?.finishReason})`);
  }

  const parsed = JSON.parse(out) as { items?: ModelItem[] };
  return parsed.items ?? [];
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
