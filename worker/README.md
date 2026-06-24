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

1. **Claude Haiku** (`claude-haiku-4-5-20251001`) decomposes the text into
   discrete food items with estimated quantity, unit, grams, and macros, using
   structured output (`output_config.format` with a JSON schema) so the reply is
   always valid JSON.
2. **USDA FoodData Central** (optional) refines each item's macros: the Worker
   searches FDC for the item's generic query, reads the per-100g
   Energy/Protein/Fat/Carb nutrients, and scales them to the estimated grams.
   This is best-effort — any lookup that fails or finds no match leaves Haiku's
   estimate in place. Without `USDA_FDC_KEY`, Haiku's estimates are returned as-is.

`needsConfirmation` is always `true`: the app shows every parsed item on the
confirmation screen for the user to adjust before saving.

## Setup & deploy

Prereqs: Node 18+, a Cloudflare account, an Anthropic API key. USDA key is
optional but recommended (free): https://fdc.nal.usda.gov/api-key-signup.html

```bash
cd worker
npm install                 # pulls @anthropic-ai/sdk, wrangler, types
npx wrangler login          # one-time Cloudflare auth

# Secrets (stored encrypted in Cloudflare — never in wrangler.toml):
npx wrangler secret put ANTHROPIC_API_KEY      # required
npx wrangler secret put USDA_FDC_KEY           # optional — enables USDA enrichment
npx wrangler secret put PROXY_SHARED_SECRET    # optional — see "Auth" below

npm run typecheck           # optional sanity check
npm run deploy              # -> https://tdee-parse-worker.<subdomain>.workers.dev
```

> Dependency versions in `package.json` are floors. If `npm install` complains,
> run `npm install @anthropic-ai/sdk@latest wrangler@latest @cloudflare/workers-types@latest typescript@latest`.

### Local dev

```bash
# Put secrets in a gitignored .dev.vars for `wrangler dev`:
printf 'ANTHROPIC_API_KEY=sk-ant-...\nUSDA_FDC_KEY=...\n' > .dev.vars
npm run dev                 # http://localhost:8787
curl -s localhost:8787/parse -H 'content-type: application/json' \
  -d '{"text":"two eggs and a bowl of oatmeal"}' | jq
```

## Auth

The endpoint is public by default (any caller can spend your Anthropic budget).
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

Haiku 4.5 is $1 / $5 per million input / output tokens. A parse is tiny —
roughly 300–500 input + 150–300 output tokens — so each call costs on the order
of **$0.001–0.002**. USDA FoodData Central is free. The dominant cost is the
Anthropic call; the Worker itself runs in Cloudflare's free tier for this volume.
