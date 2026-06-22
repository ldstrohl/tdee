# Project Specification: Personal Macro & Expenditure Engine (Codename: Project TDEE)

## Project Overview
A single-user, local-first Android app that replaces dropdown-driven food logging with
natural-language entry, ingests bodyweight automatically from Health Connect, and
mathematically derives an **empirical, dynamic Total Daily Energy Expenditure (TDEE)** by
matching net caloric intake against the smoothed weight trend. It then proposes calorie and
macro targets on a weekly cadence (MacroFactor-style "check-in").

**MVP scope is deliberately narrow:** prove the core loop end-to-end for one user on one phone.

```
NL food log -> macros -> weight sync -> empirical TDEE -> weekly target adjustment
```

---

## Locked Decisions (from spec review)
| Decision | Choice | Consequence |
|---|---|---|
| Platform | Android only, native | Kotlin + Jetpack Compose. No iOS, no cross-platform layer. |
| Users | Single user (just me) | No signup UI, but user identity + auth sit behind seams (see Design Principles), so multi-user is additive, not a rewrite. |
| Units | Display/input **lbs**; store + compute canonical **kg** | One conversion boundary in the UI; engine stays in kg / 7700 kcal-per-kg. |
| Weight ingestion | Health Connect (pull) + manual fallback | No Withings, no HealthKit, no webhooks. |
| LLM key location | Thin serverless proxy | Anthropic key + parsing prompt live server-side. App data stays on-device. |
| Advanced features in v1 | CSV/JSON export + goal projection | Voice, barcode, E2E encryption, PDF reports → Phase 2. |

---

## Canonical Units
The internal system is fixed and **dimensionally consistent** — all engine math and storage use it,
conversions happen only at the human-display edge:

| Dimension | Canonical unit | Notes |
|---|---|---|
| Mass | **kg** | Native to Health Connect; display converts to **lb**. |
| Energy | **kcal** | Native to every energy source (USDA, food labels, Mifflin–St Jeor) and the user. `energy_density` carries `kcal/kg` (default 7700). |
| Length | **cm** | As Mifflin–St Jeor expects; height input. |
| Time | **`java.time`** (`Instant`/`LocalDate`) | Windows expressed in days. |

We deliberately **do not** convert energy to joules: kcal is the lingua franca of all energy I/O,
so joules would *add* conversion boundaries (USDA, formula, constant, UI) rather than remove them.
Correctness comes from dimensional consistency (which holds) and from unit-suffixed field names
(`value_kcal`, `*_g`, `*_kg`) — not from re-denominating to SI. Display-layer conversion (kg→lb,
and kcal→kJ if ever needed) is the only place units change.

---

## Design Principles (Seams, not features)
These keep the MVP small while avoiding one-way doors. Each is a thin seam today, not a built-out
system:
* **Units boundary.** Canonical storage and all engine math in **kg** (7700 kcal/kg). Convert
  to/from **lbs** only in the UI input/display layer. A future kg toggle is a display change.
* **Current-user seam.** Entities reference a `user_id`; the app reads "current user" through a
  single provider that today returns a constant. Multi-user = wiring real auth into that provider,
  not reshaping tables.
* **Proxy auth seam.** App↔Worker auth is one shared-secret header now, behind an auth interface;
  it can grow to per-user tokens without touching call sites.
* **Configurable windows.** Smoothing window and TDEE window are user settings (defaults 14),
  read by the engine and the scenario explorer — never hardcoded.
* **Estimation seam.** The engine is a time-queryable estimator over a raw timestamped weight
  stream + completeness-aware intake, emitting
  `TdeeEstimate { value_kcal, method, uncertainty_kcal, calibrating }`. Today's trivial rule
  (calibrating while in the formula/blend window) can be swapped for a real state-space estimator
  (EKF) reporting posterior SE — UI consumers only read the flag and the number, never the internals.

---

## Architecture & System Overview

```
                          ANDROID DEVICE (local-first)
  +-----------------------------------------------------------------+
  |  Jetpack Compose UI                                             |
  |   - Log screen (NL text in)         - Dashboard (Vico charts)  |
  |   - Confirmation screen             - Weekly check-in          |
  |   - Onboarding / profile            - Export                   |
  |                                                                |
  |  Domain layer (Kotlin)                                          |
  |   - Math & Analytics Engine (EMA, TDEE, target calc)          |
  |   - Health Connect sync (foreground + periodic WorkManager)   |
  |                                                                |
  |  Room (SQLite)  <-- single source of truth                     |
  |   (backed up via Android Auto Backup)                          |
  +----------------------------+------------------------------------+
                               |  HTTPS (only network dependency for logging)
                               v
                   +-----------------------------+
                   |  Cloudflare Worker (proxy)  |
                   |   POST /parse               |
                   |   1. Haiku JSON extract     |
                   |   2. USDA FDC match         |
                   |   3. normalize -> grams     |
                   |   4. compute macros         |
                   +-----------------------------+
                        |                  |
                        v                  v
                  Anthropic API       USDA FoodData Central
```

The proxy is the **only** backend. Everything else (weight, history, math, charts) runs and
persists on-device, so the app is fully usable offline except for the moment of parsing a new
food entry.

---

## Data Model (Room entities)

```
UserProfile (single row today; carries user_id for the multi-user seam)
  id, user_id, sex, birth_year, height_cm,
  activity_level (enum: sedentary..very_active),
  goal_rate_kg_per_week (float, negative = loss, 0 = maintain; DEFAULT 0 = maintenance),
  goal_weight_kg (nullable),
  protein_g_per_kg (default 2.0), fat_pct_of_calories (default 0.25),
  day_start_hour (int 0-23, DEFAULT 0 = midnight; custom day boundary),
  smoothing_window_days (default 14),
  tdee_window_days (default 14),
  created_at, updated_at
  -- canonical units are kg/cm; lbs is a display concern only

WeightEntry                     -- raw timestamped samples; NOT pre-aggregated to one/day
  id, timestamp, weight_kg, body_fat_pct (nullable),
  source (enum: health_connect | manual),   -- engine derives measurement quality from source today
  health_connect_uid (nullable, for dedup), created_at

FoodEntry
  id, timestamp (log day = timestamp shifted by profile.day_start_hour),
  raw_text, name, brand (nullable),
  quantity, unit, grams,
  kcal, protein_g, fat_g, carb_g,
  fdc_id (nullable), source_db (enum: usda | manual),
  created_at, updated_at        -- editable & soft-deletable

TargetPeriod                    -- one per accepted weekly check-in
  id, start_date, end_date,
  tdee_at_checkin,
  calorie_target, protein_target_g, fat_target_g, carb_target_g,
  accepted_at

WeightTrendCache (derived, recomputable)
  date (PK), ema_kg, tdee_estimate, tdee_method (formula|blend|empirical),
  uncertainty_kcal, calibrating
```

**Derived views the engine consumes (computed, not stored):**
* `DailyIntake { date, kcal, complete }` — sums `FoodEntry` per log-day. `complete = (entry count
  > 0)` for the MVP heuristic; the **in-progress current day is always treated as incomplete** and
  excluded from windows. Seam: `complete` becomes a confidence *weight* (and can be user-overridden)
  later, not a hard boolean.
* `WeightSample { t, kg, quality }` — the raw `WeightEntry` stream; `quality` is derived from
  `source` today (manual vs Health Connect), and becomes real per-sample measurement variance when
  a state-space estimator lands.

Derived values (daily totals, EMA series, current TDEE) are **computed from these tables**, not
hand-entered. The cache table exists only to avoid recomputing the full series on every render;
it can be dropped and rebuilt at any time (estimates are revisable; see Module 4).

---

## Component Specifications (MVP)

### 0. Onboarding & User Profile  *(NEW — required for MVP)*
**Objective:** Collect the minimum inputs the math engine needs before any empirical data exists.
* **Captured once:** sex, birth year, height, current bodyweight (seeds first `WeightEntry`),
  activity level, and a **goal expressed as a rate** (e.g. −0.5 kg/week, or 0 for maintain).
* **Optional advanced:** protein g/kg and fat % overrides (sensible defaults otherwise).
* **Health Connect permission request** happens here (read Weight; read Body Fat if available).
* **Verify:** profile persists; re-opening onboarding shows saved values; missing required fields
  block exit.

### 1. Natural Language Layer (Food Parser)
**Objective:** Convert free text into a structured, macro-resolved list of food components.
* **Flow:** Log screen text field → `POST /parse` → confirmation screen → write `FoodEntry` rows.
* **Engine:** `haiku` in strict JSON mode (server-side, in the proxy).
* **Confirmation screen (required):** shows each parsed item with its matched food, estimated
  grams, and computed macros; user can edit quantity/unit, swap the match, delete an item, or add
  one manually before committing. Low-confidence matches are flagged.
* **Offline behavior:** if the proxy is unreachable, allow a fully manual entry path (name +
  grams + macros) so logging never hard-blocks.
* **Verify:** "2 eggs and a cup of oatmeal" yields two editable items with non-zero macros that
  persist after confirmation.

### 2. Nutrition Database Integration
**Objective:** Resolve parsed items to factual macro/calorie values.
* **Database (MVP):** USDA FoodData Central API (free, public domain) for whole/generic foods.
  Queried **inside the proxy**, key held server-side.
* **Matching:** proxy searches FDC by name + modifiers, selects best match, normalizes the
  portion to grams, and returns macros per the actual quantity (FDC values are per 100 g).
* **Deferred → Phase 2:** Open Food Facts + barcode scanning for branded/packaged foods.
* **Verify:** a known food ("100 g chicken breast") returns macros within tolerance of FDC.

### 3. Weight Tracking & Device Integration (Biometrics)
**Objective:** Ingest bodyweight with minimal friction.
* **Primary source:** **Health Connect** (Android). *Correction from original spec: Health
  Connect is **pull-based**, not webhook/push.* Sync on **app foreground** plus a **periodic
  `WorkManager` job** (e.g. every 6 h). De-duplicate against `health_connect_uid`.
* **Fallback:** manual weight entry UI (also covers users mid-setup or without a smart scale).
* **Captured:** weight (stored canonical kg, entered/displayed in lbs), and body-fat % when the
  source provides it.
* **Verify:** a weight written by any Health-Connect-writing app/scale appears in the app after a
  sync, exactly once.

### 4. Math & Analytics Engine  *(on-device Kotlin)*
**Objective:** Strip noise from weight and compute empirical expenditure.

* **I/O contract (estimator-ready).** The engine is a **time-queryable estimator**, not a
  current-value calculator:
  ```
  inputs:  WeightSample[] { t, kg, quality }       // raw timestamped stream, NOT a daily grid
           DailyIntake[]  { date, kcal, complete }  // incomplete + in-progress days excluded
           UserProfile (windows, goal, energy_density)
  api:     estimateAt(t = now) -> TdeeEstimate
           weightTrendAt(t = now) -> trend_kg
  ```
  Framing the API around `t` (rather than "compute the current scalar") is what lets a smoother
  later **revise past estimates** as new data arrives, and lets the projection treat TDEE as a
  trajectory. **Estimates are revisable; decisions are frozen** — `WeightTrendCache` may be dropped
  and rebuilt, but `TargetPeriod.tdee_at_checkin` is an immutable record of what we believed when
  targets were set, and must never be back-filled from the cache.

* **Per-day weight aggregation (rule, not assumption).** The EMA runs on one value per day, but
  multiple weigh-ins per day are expected (morning vs night differ by 1–2 lb). MVP rule:
  **first measurement of the log-day** wins; aggregation is internal to the engine, so the raw
  stream stays intact for a future estimator that consumes all samples directly.

* **Weight smoothing — N-day EMA (default 14).** `alpha = 2 / (N + 1)`, N = 14 (`alpha ≈ 0.133`).
  `EMA_today = alpha * weight_today + (1 - alpha) * EMA_prev`. On days with no measurement,
  carry `EMA_prev` forward.

* **Empirical TDEE over a rolling window W (default 14 days):**
  ```
  avg_intake        = mean(kcal over COMPLETE days in W)   # incomplete days excluded, not zero-filled
  trend_delta_kg    = EMA(end of W) - EMA(start of W)
  stored_kcal_per_day = (trend_delta_kg * energy_density) / W   # energy_density default 7700 kcal/kg
  TDEE              = avg_intake - stored_kcal_per_day
  ```
  (Losing weight → `trend_delta_kg` negative → TDEE > intake, as expected.)
  **Missing-intake guard:** unlogged days are *missing data*, not 0-calorie days — averaging them
  in poisons the estimate. Only `DailyIntake.complete` days count toward `avg_intake`; if too few
  complete days exist in W, the estimate stays in the calibrating regime (below). `energy_density`
  is a named parameter, not an inline literal (see C-notes — composition-aware partitioning later).

* **Cold-start / bootstrap (required):** before ~14 days of paired intake+weight data exist, use
  a formula seed — **Mifflin–St Jeor RMR × activity factor**. Blend linearly from 100% formula at
  day 0 to 100% empirical at day 14, so targets are sane from day one. `tdee_method` records which
  regime produced each value.

* **Target calculation (rate-based):**
  ```
  daily_adjustment = goal_rate_kg_per_week * energy_density / 7
  calorie_target   = current_TDEE + daily_adjustment
  ```
  Macros from the calorie target:
  ```
  protein_g = protein_g_per_kg * bodyweight_kg          # default 2.0 g/kg
  fat_g     = (calorie_target * fat_pct) / 9            # default 25%
  carb_g    = (calorie_target - protein_g*4 - fat_g*9) / 4
  ```
* **Configurable windows.** Smoothing N (`smoothing_window_days`) and TDEE window W
  (`tdee_window_days`) come from `UserProfile` (defaults 14), not constants — they feed both the
  live engine and the scenario explorer (4b).
* **Engine output & uncertainty (estimation seam).** The engine returns a `TdeeEstimate`, never a
  bare number:
  ```
  TdeeEstimate {
    value_kcal,
    method,             // formula | blend | empirical
    uncertainty_kcal,   // standard error in KCAL (stable unit, not an abstract score)
    calibrating: Bool   // engine-owned; = uncertainty_kcal > calibration_threshold_kcal
  }
  ```
  **Why kcal, not an abstract score:** the MVP proxy and a future EKF posterior must share a unit,
  or the threshold silently means two different things. A standard error in kcal is meaningful now
  (a coarse SE from data volume), meaningful later (posterior SE), and is exactly what the Module-4b
  projection confidence band consumes. Consumers read `calibrating` + `value_kcal`; they never
  interpret the raw uncertainty themselves.
  **MVP rule (intentionally trivial):** during the formula/blend regime treat the estimate as
  calibrating — `calibrating = complete_paired_days < tdee_window_days`, with `uncertainty_kcal` a
  coarse SE that shrinks as complete paired days accrue. This is the **single seam** where a
  sophisticated estimator drops in later — e.g. an **EKF / state-space model jointly tracking true
  bodyweight and TDEE** populates `uncertainty_kcal` with a real posterior SE, and `calibrating`
  falls out of the same threshold with **no call-site changes**.
* **Cadence:** recompute the TDEE/EMA series whenever data changes (cheap), but targets only
  change at check-in (see Module 8) to avoid mid-day drift.
* **Verify:** unit tests cover EMA on a fixed series, multiple-weigh-ins-per-day aggregation,
  exclusion of incomplete/in-progress intake days from `avg_intake`, the empirical TDEE sign
  convention, the formula→empirical blend boundary, macro arithmetic summing back to the calorie
  target, and `calibrating` flipping false once enough complete paired days accrue.

* **C-notes (parameterize now, build later — not MVP scope):**
  * *Energy density:* `energy_density` (default 7700 kcal/kg) assumes 100% fat-mass change. With
    `body_fat_pct` already captured, composition-aware partitioning later splits this into fat vs
    lean, and the scalar `ema_kg` trend output grows into a fat/lean state vector.
  * *Nutrient set:* `FoodEntry` has fixed kcal/protein/fat/carb columns; fiber/sugar/sodium/micros
    are a **migration**, not an additive change.
  * *Target shape:* one constant daily target per `TargetPeriod`; calorie/macro cycling (training
    vs rest days) would require targets to vary within a period.

### 4b. Goal Projection & Scenario Exploration  *(added per request)*
**Objective:** Let the user explore goals and filter windows and *see the predicted impact*, not
just current state — e.g. overlay a goal trajectory on the actual trend and show the predicted
date of hitting a target weight.
* **Live-adjustable inputs:** goal weight; one of {target rate (lb/wk), target intake (kcal/day),
  target date} with the other two derived; plus smoothing N and TDEE window W.
* **Projection (linear, static-TDEE):** feeds off the engine's queryable API —
  `TDEE = estimateAt(now).value_kcal`, `trend_kg_now = weightTrendAt(now)`.
  ```
  rate_kg_per_day = (scenario_intake - TDEE) / energy_density
  days_to_goal    = (goal_kg - trend_kg_now) / rate_kg_per_day      # only if sign is valid
  predicted_date  = today + days_to_goal
  ```
  **Reachability guard:** if the rate is zero or the wrong sign for the goal (e.g. a surplus while
  trying to lose), surface "not reachable at this intake" instead of a date.
* **Honesty note:** this assumes constant TDEE; real weight loss flattens as mass (and RMR) fall,
  so a linear line *over-predicts* speed. v1 ships the linear model **labeled as an estimate**;
  adaptive/declining-TDEE projection is Phase 2.
* **Calibrating state:** when the feeding `TdeeEstimate.calibrating` is true, suppress the precise
  predicted date and show a "still calibrating" treatment instead of a falsely confident number.
  Once `uncertainty_kcal` carries a real SE, this is where a projection confidence band lands
  (Phase 2).
* **MVP cut:** one active scenario overlaid at a time. Saving and side-by-side comparison of
  multiple scenarios → Phase 2.
* **Verify:** a deficit intake produces a downward projection with a plausible predicted date;
  switching to surplus inverts it; an impossible goal shows the guard message; changing N or W
  visibly updates the trend/TDEE feeding the projection.

### 5. Visualization Layer
**Objective:** Actionable insight, not decoration. **Library: Vico (Compose).**
* **Trend graph:** raw daily weight points overlaid on the 14-day EMA line.
* **Expenditure graph:** daily intake bars vs the moving TDEE line; deficit/surplus shaded.
* **Macro donut / today view:** protein/fat/carb and calories consumed vs the current
  `TargetPeriod` limits.
* **Goal projection / what-if graph (Module 4b):** historical raw weight + EMA trend, then a
  **dashed projected trend** forward to the goal weight, with a marker/annotation at the predicted
  attainment date. Scenario controls (goal, rate/intake/date, N, W) update it live so the user can
  feel the impact of different goals and filter windows.
* **Calibrating indicator:** wherever current TDEE is shown (dashboard, projection), render a
  "calibrating" badge when `TdeeEstimate.calibrating` is true, so early/low-confidence numbers
  read as provisional.
* **Verify:** charts render from real Room data and update after a new log/weight entry; the
  projection overlay redraws as scenario inputs change.

### 6. Persistence & Backup
**Objective:** Durable, low-effort data persistence for a single user.
* **Store:** Room (SQLite) on-device — the local-first source of truth.
* **Backup (MVP):** enable **Android Auto Backup** for the app (manifest flag) so the DB
  restores on reinstall/new device via the user's Google account. No server-side data store.
* **Deferred → Phase 2:** active cloud sync, multi-device, E2E-encrypted backups with a
  user-managed key.
* **Verify:** uninstall/reinstall restores prior entries (Auto Backup), and a DB migration test
  passes across a schema version bump.

### 7. Export
**Objective:** User owns their data, plainly.
* **CSV/JSON dump:** one button producing dated rows for weight, smoothed weight, kcal, protein,
  carbs, fat, and computed TDEE; shared via Android share sheet.
* **Deferred → Phase 2:** PDF progress report (30/60/90-day) for trainers/clinicians.
* **Verify:** exported file opens in a spreadsheet with correct columns and row counts matching
  the DB.

### 8. Check-in (Target Readjustment)
**Objective:** MacroFactor-style check-in — controlled target updates, but with full user control.
* **Active targets:** the dashboard's targets come from the most recent `TargetPeriod` (falling back
  to live `proposedTargets()` until the first check-in exists).
* **Check-in (two triggers):** recompute empirical TDEE → propose new calorie + macro targets →
  on accept, write a new `TargetPeriod` **effective immediately**. Triggered either by the **weekly
  "check-in due" prompt** OR **on-demand by the user at any time** ("Check in now").
* **Manual override applies immediately:** the user may **edit the calorie/macro target directly at
  any time** (mid-week) — this takes effect immediately (writes/updates the active `TargetPeriod`).
  *(Revised from the original "immutable within a period" rule in favor of user control.)*
* **The invariant kept:** the **app never changes targets on its own** — targets change only via a
  check-in accept or an explicit user edit. (That's the "no silent drift" intent, preserved.)
* **Verify:** weekly or on-demand check-in proposes targets and, on accept, creates a `TargetPeriod`
  the dashboard uses immediately; a manual mid-week target edit takes effect immediately; with no
  check-in and no user edit, targets never change on their own.

### 9. Privacy & Local-First Guardrails
**Objective:** Health data stays on the device by default.
* **Architecture:** local-first by construction — all health/meal/weight data lives in on-device
  Room. The only network call is the per-entry `/parse` request, which sends **food text only**
  (never weight, history, or identity).
* **Proxy hygiene:** Worker is stateless, logs no request bodies, holds only the Anthropic + USDA
  keys.
* **Health Connect:** request the minimum read scopes; surface a clear permission rationale.
* **Deferred → Phase 2:** end-to-end encrypted cloud backup with a user-managed key.
* **Verify:** with networking disabled, every feature except new-food parsing works; `/parse`
  request payloads contain no biometric data.

### 10. Historical Data Import / Backfill (Pre-seed)
**Objective:** let a new user import existing history so the empirical TDEE engine has data right away
and **shortens or skips the ~2-week calibration phase** instead of starting from zero.
* **Sources:**
  * **Health Connect weight history** — on connect, pull *existing past* weight records (not just
    ongoing sync), de-duplicated and backdated to their real timestamps. (Extends Module 3.)
  * **Intake backfill** — enter previously-tracked calories/macros for past days via a bulk/manual
    "add for a past date" path (e.g. the ~week a user already logged elsewhere); CSV import is a
    nice-to-have.
* **Mechanism:** the engine already consumes backdated entries (timestamps drive log-day + windows +
  completeness), so imported history flows through EMA / empirical TDEE / calibrating exactly like
  live data — more complete paired days ⇒ the estimate leaves the calibrating regime sooner. (The
  debug `seedSampleData()` already proves backdated insertion works end-to-end.)
* **MVP scope:** manual backfill for past dates (weight + intake) **and** the Health Connect history
  pull on connect; CSV import deferred.
* **Verify:** importing N days of past weight + complete intake moves the estimate from
  formula/blend toward empirical and shrinks the `calibrating` window accordingly.

---

## MVP Definition of Done (the core loop, proven)
1. Onboard → profile + Health Connect permission saved.
2. Log "chicken and rice" → confirm → entry with real macros in Room.
3. A weight from Health Connect (or manual) appears, de-duplicated.
4. Dashboard shows EMA trend + TDEE (formula-seeded early, empirical after ~2 weeks).
5. Goal-projection overlay shows a predicted attainment date for a chosen scenario, and updates
   when the goal/intake/window inputs change.
6. Check-in (weekly or on-demand) proposes new calorie + macro targets effective on accept; manual
   target edits apply immediately; the app never changes targets on its own.
7. Export produces a correct CSV/JSON.
8. Reinstall restores data via Auto Backup.

---

## Explicitly Deferred (Phase 2+)
- Voice / speech-to-text logging
- Barcode scanning + Open Food Facts (branded foods)
- iOS / HealthKit / cross-platform
- Active cloud sync, multi-device, and E2E-encrypted backups (user-managed key)
- PDF report generation
- Recent foods / favorites / meal templates *(strong Phase-2 usability win; reduces LLM calls)*
- Adaptive (declining-TDEE) goal projection + saving/comparing multiple scenarios side-by-side
- Withings or other direct device APIs beyond Health Connect

---

## Resolved Decisions
1. **Units:** display/input **lbs**, store + compute canonical **kg** (one UI conversion boundary).
2. **Goal default:** **maintenance** (rate = 0); onboarding/settings sets a cut/bulk rate.
3. **Day boundary:** **configurable** `day_start_hour`, **default midnight (0)**.
4. **Proxy auth:** **single shared-secret header**, kept simple but placed behind an auth seam so
   per-user tokens are an additive change later.
5. **TDEE window W:** **user-configurable**, **default 14 days**; feeds engine + scenario explorer.
6. **Scenario exploration:** in scope for v1 as a linear static-TDEE projection (Module 4b);
   adaptive projection and multi-scenario compare are Phase 2.
```
