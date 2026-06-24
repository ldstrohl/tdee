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
 * Pipeline: Google Gemini decomposes the free text into structured items with
 * estimated quantities and macros (structured-output JSON). If USDA_FDC_KEY is
 * configured, each item's macros are then refined from USDA FoodData Central,
 * scaled to the estimated grams. USDA enrichment is best-effort: any lookup that
 * fails or finds no good match leaves Gemini's estimate in place.
 */

interface Env {
  GEMINI_API_KEY: string;
  GEMINI_MODEL?: string; // defaults to DEFAULT_MODEL (set as a [vars] entry in wrangler.toml)
  USDA_FDC_KEY?: string;
  PROXY_SHARED_SECRET?: string;
}

const DEFAULT_MODEL = "gemini-2.5-flash";

const SYSTEM_PROMPT = `You convert a free-text meal description into structured food items.

Rules:
- Split the description into discrete foods. Do not split a single named dish (e.g. "chicken sandwich") into its components.
- Estimate a reasonable quantity, unit, and total grams for each item based on the description and typical serving sizes.
- Estimate calories and macronutrients for the whole quantity (not per 100g).
- "query" is a short, generic search phrase for a nutrition database (e.g. "scrambled eggs", "cooked oatmeal") — no quantities, no brand names unless the user named one.
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
          query: { type: "STRING", description: "Generic nutrition-database search phrase" },
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

    const items = await Promise.all(
      modelItems.map((m) => enrichWithUsda(env, m)),
    );

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
      // thinkingBudget 0 disables Gemini 2.5 "thinking" — deterministic, cheap
      // JSON extraction. Only valid on 2.5 models; remove if GEMINI_MODEL is 1.5/2.0.
      thinkingConfig: { thinkingBudget: 0 },
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

/**
 * Refine one item's macros from USDA FoodData Central, scaled to the model's
 * estimated grams. Best-effort: on any failure, return the model's estimate
 * unchanged (with needsConfirmation = true added).
 */
async function enrichWithUsda(env: Env, m: ModelItem): Promise<ParsedFoodItem> {
  const base: ParsedFoodItem = { ...m, needsConfirmation: true };
  if (!env.USDA_FDC_KEY || !(m.grams > 0)) return base;

  try {
    const per100 = await usdaPer100g(env.USDA_FDC_KEY, m.query);
    if (!per100) return base;
    const factor = m.grams / 100;
    return {
      ...base,
      kcal: round(per100.kcal * factor),
      proteinG: round(per100.proteinG * factor),
      fatG: round(per100.fatG * factor),
      carbG: round(per100.carbG * factor),
    };
  } catch {
    return base;
  }
}

interface Per100g {
  kcal: number;
  proteinG: number;
  fatG: number;
  carbG: number;
}

// USDA FoodData Central nutrient numbers (per 100g for Foundation / SR Legacy).
const NUTR_ENERGY_KCAL = 1008;
const NUTR_PROTEIN = 1003;
const NUTR_FAT = 1004;
const NUTR_CARB = 1005;

async function usdaPer100g(apiKey: string, query: string): Promise<Per100g | null> {
  const u = new URL("https://api.nal.usda.gov/fdc/v1/foods/search");
  u.searchParams.set("query", query);
  u.searchParams.set("pageSize", "1");
  u.searchParams.set("dataType", "Foundation,SR Legacy");
  u.searchParams.set("api_key", apiKey);

  const res = await fetch(u.toString());
  if (!res.ok) return null;

  const data = (await res.json()) as {
    foods?: Array<{ foodNutrients?: Array<{ nutrientId?: number; value?: number }> }>;
  };
  const food = data.foods?.[0];
  if (!food?.foodNutrients) return null;

  const byId = new Map<number, number>();
  for (const n of food.foodNutrients) {
    if (typeof n.nutrientId === "number" && typeof n.value === "number") {
      byId.set(n.nutrientId, n.value);
    }
  }
  // Energy must be present for the match to be usable.
  const kcal = byId.get(NUTR_ENERGY_KCAL);
  if (kcal == null) return null;

  return {
    kcal,
    proteinG: byId.get(NUTR_PROTEIN) ?? 0,
    fatG: byId.get(NUTR_FAT) ?? 0,
    carbG: byId.get(NUTR_CARB) ?? 0,
  };
}

function round(n: number): number {
  return Math.round(n);
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
