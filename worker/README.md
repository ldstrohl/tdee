# tdee-parse-worker

A thin Cloudflare Worker that turns a free-text meal description into structured
macros. It backs the Android app's `WorkerFoodParser` seam (Module 1–2 of
`outline.md`).

```
POST /parse
  body:   { "text": "two eggs and a bowl of oatmeal" }
  reply:  { "items": [
            { "name": "Scrambled eggs", "displayQuantity": 2, "unit": "egg",
              "grams": 100, "kcal": 143, "proteinG": 13, "fatG": 10, "carbG": 1,
              "needsConfirmation": true },
            ...
          ] }
```

Each item matches `com.tdee.app.data.ParsedFoodItem` field-for-field, so
`WorkerFoodParser` can deserialize the array directly.

## How it works

**Google Gemini** (default `gemini-2.5-flash`, thinking enabled) decomposes the
text into discrete food items with estimated quantity, unit, grams, and macros.
It uses Gemini structured output (`responseMimeType: "application/json"` +
`responseSchema`) so the reply is always valid JSON in the expected shape, and
transient `503`/`429`/`500` responses are retried briefly. Gemini's holistic
estimates are returned as-is.

`needsConfirmation` is always `true`: the app shows every parsed item on the
confirmation screen for the user to adjust before saving — that's the accuracy
backstop.

> An earlier version refined macros against **USDA FoodData Central** (top-1 FDC
> match per 100g, scaled to the estimated grams). For free-text descriptions the
> top-1 match was unreliable (e.g. "coffee" → a caloric product at ~294 kcal), so
> it was removed in favor of Gemini's holistic estimates. Revisit only with
> better matching (ranked candidates / disambiguation) if needed.

It's a pure-`fetch` Worker — no npm runtime dependencies, no `nodejs_compat`.

## Setup & deploy

Prereqs: **Node 22+** (wrangler 4 requires it — use nvm), a Cloudflare account, a
Gemini API key (https://aistudio.google.com/apikey).

```bash
cd worker
npm install                 # wrangler + types only
npx wrangler login          # one-time Cloudflare auth (opens a browser)

# Secrets (stored encrypted in Cloudflare — never in wrangler.toml):
npx wrangler secret put GEMINI_API_KEY         # required
npx wrangler secret put PROXY_SHARED_SECRET    # optional — see "Auth" below

npm run typecheck           # optional sanity check
npm run deploy              # -> https://tdee-parse-worker.<subdomain>.workers.dev
```

### Choosing the model

`GEMINI_MODEL` is a plain (non-secret) var in `wrangler.toml`, default
`gemini-2.5-flash`. Change it there, or override per-deploy. **Note:** the
request sets `thinkingConfig: { thinkingBudget: 0 }`, which is only valid on
Gemini **2.5** models — if you switch to a 1.5/2.0 model, remove that line in
`src/index.ts` (it's commented).

### Local dev

```bash
# Put secrets in a gitignored .dev.vars for `wrangler dev`:
printf 'GEMINI_API_KEY=...\n' > .dev.vars
npm run dev                 # http://localhost:8787
curl -s localhost:8787/parse -H 'content-type: application/json' \
  -d '{"text":"two eggs and a bowl of oatmeal"}' | jq
```

## Auth

The endpoint is public by default (any caller can spend your Gemini quota).
If you set `PROXY_SHARED_SECRET`, the Worker requires
`Authorization: Bearer <secret>` on every request and rejects others with 401.
Recommended for a deployed proxy — store the same secret in the app and send it
with each `/parse` call.

## Wiring the app

Implement `WorkerFoodParser : FoodParser` in the app, POST `{ text }` to
`<worker-url>/parse`, deserialize `items` into `List<ParsedFoodItem>`, and swap
it for `LocalHeuristicFoodParser` in `AppContainer`. Add the Worker URL (and the
shared secret, if used) to app config; an HTTP client dependency (OkHttp/Ktor)
is needed at that point.

## Cost

A parse is small — roughly 300–500 input tokens plus output (more than before now
that thinking is on, but still well under a cent per call on `gemini-2.5-flash`).
The Gemini API has a free tier with per-minute/day limits that comfortably covers
personal use. The Worker itself runs in Cloudflare's free tier at this volume.
Current Gemini pricing: https://ai.google.dev/gemini-api/docs/pricing
