# Project TDEE — Working Notes

A personal/limited-release **Android** app: natural-language food logging → macros, automatic
bodyweight ingestion (Health Connect), and an **empirical dynamic TDEE** that drives weekly target
adjustments (MacroFactor-style). Full design spec is in **`outline.md`** — that is the source of
truth for requirements/decisions; this file is operational guidance.

## Architecture (two Gradle modules)
- **`:domain`** — pure Kotlin **JVM** library, **zero Android deps** (kotlin stdlib + `java.time` only).
  Holds the math: `TdeeEngine` (interface = the future-EKF seam) + `DefaultTdeeEngine`,
  `TargetCalculator`, `GoalProjector`, and `Types.kt`. Tests are fast JUnit5, run with no SDK.
- **`:app`** — Android app. Room data layer in `com.tdee.app.data` (entities suffixed `*Entity`,
  DAOs, `AppDatabase`, `Converters`, `Mappers`, `TdeeRepository`) + Compose UI. Tests use Robolectric.
- `:app` depends on `:domain`, never the reverse. The dependency boundary is compiler-enforced.

`TdeeRepository` is the single seam the UI uses: loads Room entities → maps to the engine's domain
views (`WeightSample`, `DailyIntake`, domain `UserProfile`) → runs the engine → returns
estimates/targets/projections.

## Toolchain & versions
JDK 17 · Gradle 8.11.1 (wrapper) · AGP 8.9.1 · Kotlin 2.0.20 · KSP 2.0.20-1.0.25 · Compose BOM
2024.09.00 · Room 2.6.1 · **compileSdk 36** / targetSdk **34** (build-tools 34.0.0 + **36.0.0**),
minSdk **26** · `androidx.health.connect:connect-client` **1.1.0** · `androidx.work:work-runtime-ktx`
2.9.1. Android SDK at `~/Android/Sdk` (`local.properties` sets `sdk.dir`). No system Gradle — use
`./gradlew`. Pin versions in `gradle/libs.versions.toml`.
*(compileSdk was bumped 34→36 + AGP/Gradle bumped so connect-client 1.1.0 — which targets platform
Health Connect — would work; the old alpha11 client was forced by compileSdk 34 and was incompatible.)*

## Build & test (run from repo root)
```
./gradlew :domain:test            # engine unit tests (fast, no SDK)
./gradlew :app:testDebugUnitTest  # Room/repository/ViewModel tests (Robolectric)
./gradlew :app:assembleDebug      # APK → app/build/outputs/apk/debug/app-debug.apk
```

## Key conventions
- **Canonical units: kcal / kg / cm / `java.time`.** Energy stays **kcal** (every source speaks it;
  joules would only add boundaries). lbs and ft-in are **display-only**, converted at the UI edge.
- **Don't bake in single-user.** This is a limited release but will gain users. Route all user-owned
  data access through a `CurrentUser` provider + `userId` (not a hardcoded `id=1` singleton). The
  repo is the single place that scopes by user.
- **Orchestration**: Sonnet for mechanical work, Opus for judgment-heavy/ambiguous work. Give each
  agent stop-and-report guardrails (no git/`--force`/`--no-verify`/schema or cross-module changes
  beyond its task). Verify (`./gradlew`, screenshots) and **commit per phase yourself** — agents leave
  changes uncommitted. Before launching an agent task, check rate limits via the `session-status`
  skill; if ≥90%, cron-wait until reset + 2 min.
- `.gitignore` excludes `build/`, `.gradle/`, `.kotlin/`, `local.properties` — never commit build
  artifacts; check `git status` before `git add`.

## Emulator (VERIFIED working — this took real fighting; read before touching it)
KVM at `/dev/kvm`, only phone system image is `system-images;android-34;google_apis_playstore;x86_64`.
Use the SDK adb: `~/Android/Sdk/platform-tools/adb`.

**Use the dedicated AVD `tdee_phone`** (created for this project; boots authorized in ~24s, confirmed
with a screenshot). Do NOT use `Phone_API34` — it's a Play Store image whose existing userdata never
trusted our adb key (stuck `unauthorized` headlessly), and I accidentally damaged it (see footguns).
If `tdee_phone` is ever gone, recreate it:
```
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java)))) ANDROID_SDK_ROOT=$HOME/Android/Sdk
echo no | ~/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n tdee_phone -k "system-images;android-34;google_apis_playstore;x86_64" --device pixel_6 --force
```

**Launch headless:**
```
export ANDROID_SDK_ROOT=$HOME/Android/Sdk ANDROID_HOME=$HOME/Android/Sdk
~/Android/Sdk/platform-tools/adb start-server          # BEFORE launch, never restart mid-boot
nohup ~/Android/Sdk/emulator/emulator -avd tdee_phone \
  -no-window -no-audio -no-boot-anim -no-metrics -gpu swiftshader_indirect -no-snapshot \
  >/tmp/emulator.log 2>&1 &
```
Wait for readiness by polling BOTH `adb devices` == `emulator-5554  device` (authorized) AND
`adb shell getprop sys.boot_completed` == `1`. Do this in a **background** Bash `until`-loop (foreground
`sleep` is blocked); have it also report process death + timeout, not just success.

**Why a fresh AVD is the fix for headless auth:** there's no "Allow USB debugging" dialog to tap.
On a brand-new AVD the emulator injects `~/.android/adbkey.pub` into the guest at the image level
before boot, so it comes up already `device` (authorized) — even for a Play Store image. An *existing*
unauthorized AVD can't be fixed headlessly (Play Store images can't be rooted to write `adb_keys`).

**Footguns that cost me an hour (do not repeat):**
- **`pkill -f "qemu-system"` kills your own shell** — the script text contains that string, so `-f`
  matches the running bash. Use name-match `pgrep qemu` / `pkill qemu` (no `-f`), or kill by pid.
- **Never `adb kill-server` while an emulator is booting** — leaves it stuck `unauthorized`.
- **Never `rm -rf` an AVD's `modem_simulator/` dir** — the emulator then hangs at modem init (qemu
  stays alive, but Android never finishes booting, so adb never sees the device). This is how I broke
  `Phone_API34`.
- One instance per AVD (else lock conflict).

**Drive it (mirrors `~/AudiobookWearOS/EmulatorReadme.md`):**
```
ADB="$HOME/Android/Sdk/platform-tools/adb"
$ADB -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5554 shell am start -n com.tdee.app/.MainActivity
$ADB -s emulator-5554 exec-out screencap -p > /tmp/shot.png   # then Read the PNG to inspect
$ADB -s emulator-5554 shell input tap X Y                     # interact
$ADB -s emulator-5554 shell uiautomator dump                  # find element bounds
```
**Tap coordinates:** get them from `uiautomator dump` (true device pixels, e.g. 1080×2400) — do
NOT eyeball the screenshot the Read tool shows (it's scaled, and guessing/­multiplying misses
targets). Parse `bounds="[x1,y1][x2,y2]"`, tap the center. Compose chips/buttons appear as text
nodes; the submit button may be below the fold — `input swipe` to scroll, then re-dump.
Use the emulator for visual sign-off of UI work (agents build + run logic tests; orchestrator
installs, launches, screenshots, and confirms rendering before committing).

**Physical device (for Health Connect / Withings testing):** WSL2 Linux adb CANNOT see USB devices.
The phone is reached only through the **Windows adb binary** (path is machine-specific — see
`CLAUDE.local.md`, gitignored, not in PATH — use the full path). So there are TWO adb worlds:
**emulator via WSL adb `~/Android/Sdk/platform-tools/adb`**, **phone via the Windows `adb.exe -s
<serial>`** (serial also in `CLAUDE.local.md`). (`adb.exe` also lists the emulator but as
`unauthorized` — ignore it; use WSL adb for the emulator.) The `~/AudiobookWearOS` project's
`CLAUDE.md` documents this same WSL/Windows-adb quirk. Historical note: an older Pixel 3 (Android 12)
test phone used the legacy standalone HC app, which `connect-client 1.1.0` couldn't drive — the
current test phone (see `CLAUDE.local.md`) is Android 14+ and uses platform HC like the emulator.

## Status
Done, committed, tested (263 unit tests green): spec → scaffold → math engine (`:domain`) → Room data
layer → `TdeeRepository` → multi-user seam → app DI/plumbing → onboarding → dashboard → routing →
navigation-compose → manual food + weight logging (`FoodParser` seam) → reactive consumed-vs-target
dashboard → **light/dark/system theming** (`SettingsScreen`, `ThemeStore`, theme-aware `ChartColors`)
→ **Insights charts** (Module 5 + 4b): **Trend** (raw + 14-day EMA + always-on goal line + toggleable
Prediction overlay = goal-pace & current-pace projections to goal with dates), **Expenditure** (intake
bars + measured-TDEE line + deficit-only shading), **Macro donut** (kcal-share ring + consumed-vs-target
bars, window selector averaging complete days only) → **Help/FAQ** screen → **Edit Profile**
(post-onboarding goal/profile edit, Settings) → **date-aware backfill** (log food/weight for past
dates — Module 10 manual pre-seed) → **Check-in** (Module 8: active `TargetPeriod`, on-demand +
weekly-due, manual target edits apply immediately) → **Health Connect** (Module 3 + weight-history
pre-seed): permission flow, Connect-in-Settings, foreground + WorkManager sync → **Export** (Module 7:
per-day CSV dump → Settings share-sheet via FileProvider). All verified on the `tdee_phone` emulator
in light & dark.

**Charts are Compose Canvas, not Vico** (full design fidelity, no dep). Geometry/look reference:
`design/charts.html` + `design/charts_gen.py`. `seedSampleData()` (debug-only button on Insights) loads
~60 days + a goal so charts/prediction populate in dev.

Layout: `com.tdee.app` → `di/`, `data/` (Room + repo + `FoodParser` + `ChartData`), `ui/theme/`
(`Theme`, `ThemeStore`, `ChartColors`), `onboarding/`, `dashboard/`, `addfood/`, `addweight/`,
`settings/`, `insights/` (`InsightsScreen`, `InsightsViewModel`, `HelpScreen`). `MainActivity` =
`observeProfile()` split (null → onboarding) then a `NavHost`
(`dashboard`/`add_food`/`add_weight`/`settings`/`insights`/`help`). UI ViewModels use the
`viewModelFactory { initializer { ... } }` + `APPLICATION_KEY` pattern.

**NL parsing (modules 1–2)** now ships as a **client-direct bring-your-own-key** parser behind the
`FoodParser` seam (`LlmFoodParser` with Gemini/OpenAI/Anthropic adapters; key entered in Settings,
stored in EncryptedSharedPreferences; no key → manual entry still works). **The Cloudflare `worker/`
is retired** (no longer referenced by the app; the directory is a standalone CF project, safe to
delete — left in place pending the user's call). (Onboarding validation-feedback + Fat-% bugs fixed.)

**User-testing-feedback run** (branch `feature/user-testing-feedback`, addresses `USER_TESTING.md`):
Phase 1 — collapsible **meal groups**, **saved-meals** library, **repeat-from-prior-day**, **food
history**, single-entry edit, `imePadding` keyboard fix, parse-confirm running totals. Phase 2 — the
BYO-key parser above. Phase 3 — **check-in macro rebalancing** (`:domain` `MacroBalancer`: editing a
macro holds the kcal target fixed + refills unlocked macros). Phase 4 — **Weight hub** (`weight/`:
HC sync + manual entry + trend chart). Phase 5 — **chart fixes + zoom** (brighter raw points;
tap-to-**Expand** a chart to a full-screen `ChartDetailScreen` with pinch-zoom / drag-pan / landscape).
Schema is now on **real Room migrations** (no more `fallbackToDestructiveMigration`).

**User-testing-feedback-2 run** (branch `feature/user-testing-feedback-2`, addresses the round-2
`USER_TESTING.md`; 391 tests green): **P1** — meal **names** on groups (schema v4→v5 `mealName` +
`MIGRATION_4_5`), groups **collapsed by default**, describe-a-meal "Add as meal" / "Save meal & add" +
Back button. **P2** — **date-navigable Dashboard** (`selectedDate` drives `dayFoods` via flatMapLatest;
day navigator ◀▶ + calendar + swipe; +Add / Describe-a-meal / Saved-meals log to the chosen day via
nav-arg VM factories — fixes "can't add saved meals to prior days"). **P3a** — Weight hub **headline**
(trend + lb/wk), trend-chart **Expand**→zoom parity, **"Re-import full history"** (`sync(fullHistory=true)`
— incremental sync only pulls records *newer* than the latest stored, so backfilled older HC weigh-ins
were never imported; chart slice/series were already correct). **P3b** — Dashboard **cards open dedicated
chart pages** (`ChartType.MACROS` added; TDEE card→Expenditure, Macros card→donut page). **P4** — LLM
parser now **surfaces the provider's real `error.message`** on terminal 400s (root cause of "Haiku didn't
work" was an Anthropic **key with no credit balance** — HTTP 400 "credit balance too low" — not a code
bug; model id/headers/shape are correct). **P5** — **TDEE engine back-test** vs a real 559-day log
(`domain/src/test/resources/TDEESampleData.csv`): engine empirical estimate reconciles to **−0.0%** of
energy-balance ground truth, EMA stable under sparse weigh-ins — **engine validated, no tuning needed**.
Branch is committed per-phase but **not yet merged/pushed** (awaiting the user's call).

**User-testing-feedback-3 run** (branch `feature/user-testing-feedback-3`, addresses the round-3
`USER_TESTING.md`; 375 tests green): **Phase A** — **6-month empirical TDEE window** (`tdeeWindowDays`
14→180, EMA smoothing stays 14) with **precision-weighted (inverse-variance) shrinkage** of the Mifflin
prior toward the empirical estimate, replacing the old linear FORMULA/BLEND scheme. Requires an
**anchored-window / actual-span** fix (`TdeeEngine.empirical()`): anchor the window start to the first
weigh-in and divide ΔEMA by the true span, so an empirical signal exists from day ~2 instead of pure
formula for 6 months. `uncertaintyKcal` is now the real posterior SE `sqrt(1/(w_f+w_e))` (σ_W/σ_I measured
from the user's own data, safe defaults below 8 obs). Validated by a data back-test on the real 559-day log
(`reports/gen_report.py` → `reports/backtest.html`, old report archived `backtest_2026-06-28.html`):
shrinkage tracks the falling TDEE **2.2× more accurately** through the ramp than a linear blend, negligible
added jitter; reconciles to energy-balance truth at **+0.7%**. Decoupled the "Calibrating" UX badge from the
averaging window via `CALIBRATION_DAYS=14` (else it would show for 6 months); method labels (BLEND/EMPIRICAL)
still track the real window. App side: `Mappers.toDomain` uses the domain default (180) for `tdeeWindowDays`
(following the `energyDensity` precedent — the column is now vestigial; **no migration**); `weightProjection`
current-pace uses a fixed 14-day lookback (not the 180-day window). **Phase B** — **scale-by-multiplier** on
every meal-logging path (`NewFoodItem.scaledBy(factor)`; optional `factor` on `logSavedMeal`/`repeatMeal`/
`repeatEntry`; shared `MealMultiplierDialog` with preset chips for Saved-meals "Log" + History "Repeat";
per-item "×" field on parse-confirm with live totals + non-lossy base values) + **save a single standalone
entry as a meal** (`saveMealFromEntry`, "Save" action on standalone rows). **Phase C** — **shared trend
panel** (`WeightTrendPanel`, primitives-driven, extracted from Insights) used by both Insights and the
**Weight screen, which now has the 🔮 Prediction feature** (goal-pace + current-pace projection lines) —
DRY, no reworked copy; Expand routes to the same `ChartDetailScreen`. All three phases verified on the
`tdee_phone` emulator. Committed per-phase (`b32ce27`, `6497e92`, `917a928`) but **not yet merged/pushed**
(awaiting the user's call). *(Note: `reports/` is gitignored — the back-test report is a local artifact.)*

**Health Connect testing (verified path):** HC needs **platform HC (Android 14+ / API 34)** — works on
the `tdee_phone` emulator. It did NOT work on the old Pixel 3 test phone (Android 12) — that used the
*legacy standalone* HC app, which `connect-client 1.1.0` doesn't drive (the permission gateway bounces;
the app never registers in HC). The current test phone (see `CLAUDE.local.md`) is Android 14+, so
platform HC applies there same as production. To verify
import on the emulator: Settings → Connect Health Connect → tap through HC "Get started" + grant Weight;
then the debug-only **"Write sample weights to HC"** button (needs WRITE_WEIGHT — grant via
`adb -s emulator-5554 shell pm grant com.tdee.app android.permission.health.WRITE_WEIGHT`), then Sync →
"imported N". Re-sync dedups to 0.

**Known bug to fix:** onboarding silently disables "Get started" with no indication of the missing
required field (a user got stuck because **Sex wasn't selected**), and the **Fat % field "(0–1)"** is
confusing (users type `25`). Add validation feedback + fix that field. Note: engine aggregates weight
first-of-log-day (a 2nd same-day weigh-in won't move the trend).
