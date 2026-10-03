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
2.9.1. `local.properties` (gitignored) sets `sdk.dir`; CI falls back to `ANDROID_HOME`. No system
Gradle and no `gradlew.bat` — run `./gradlew` from **Git Bash** (needs `JAVA_HOME` = a JDK 17). Pin
versions in `gradle/libs.versions.toml`.
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
  changes uncommitted.
- `.gitignore` excludes `build/`, `.gradle/`, `.kotlin/`, `local.properties` — never commit build
  artifacts; check `git status` before `git add`.

## Devices: emulator + phone (Windows host, one adb)
Native Windows: a single Windows adb server (`adb` from SDK `platform-tools`, on PATH) sees the
emulator, the USB phone and the Wi-Fi watch — always pass `-s <serial>`. Machine-specific serials
and paths live in `CLAUDE.local.md` (gitignored).

**AVD: `dev_phone`** (API 36, Google Play, phone; shared with the AudiobookWearOS project, which also
uses `dev_watch`). Manage AVDs in Android Studio's Device Manager — `cmdline-tools`/`avdmanager`
are not installed. List: `emulator -list-avds` (`emulator.exe` is in SDK `emulator/`, not on PATH).
Launch headless from Git Bash as a **background** command (foreground `sleep` is blocked):
```
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd dev_phone -no-window -no-audio -no-boot-anim
```
Ready when `adb devices` shows `emulator-5554  device` AND `adb -s emulator-5554 shell getprop
sys.boot_completed` == `1` — poll both in a background `until`-loop (or Monitor) that also reports
process death/timeout. Stop with `adb -s emulator-5554 emu kill`.

**Footguns (hard-won):**
- **Git Bash mangles device paths:** `adb shell ls /sdcard/x` becomes `C:/Program Files/Git/sdcard/x`.
  Quote the whole remote command (`adb shell 'ls /sdcard/x'`, same for `exec-out`) or prefix
  `MSYS_NO_PATHCONV=1` (needed for `adb pull/push /sdcard/...`).
- **PowerShell 5.1 corrupts binary redirects** — never `adb exec-out screencap -p > shot.png` in
  PowerShell; do it in Git Bash, or `screencap` to `/sdcard` then `adb pull`.
- **Never overwrite `%USERPROFILE%\.android\adbkey`** — the phone and watch trust it. A fresh AVD gets
  its `.pub` injected at first boot, so it should come up authorized with no dialog (proven under
  WSL; same emulator mechanism on Windows); an existing AVD stuck
  `unauthorized` can't be fixed headlessly (Play images can't be rooted) — recreate it instead.
- **Never `adb kill-server`** while an emulator is booting (leaves it `unauthorized`); it also drops
  the phone/watch connections.
- **Never delete an AVD's `modem_simulator/` dir** — Android then hangs at modem init and never boots.
- One emulator instance per AVD (lock conflict).

**Drive it** (Git Bash; `S=emulator-5554` or the phone serial):
```
adb -s $S install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $S shell am start -n com.tdee.app/.MainActivity
adb -s $S exec-out screencap -p > "$TEMP/shot.png"     # then Read the PNG
adb -s $S shell 'uiautomator dump /sdcard/ui.xml' && adb -s $S exec-out 'cat /sdcard/ui.xml'
adb -s $S shell input tap X Y
```
**Tap coordinates:** take them from `uiautomator dump` (true device pixels) — do NOT eyeball the
screenshot the Read tool shows (it's scaled). Parse `bounds="[x1,y1][x2,y2]"`, tap the center.
Compose chips/buttons appear as text nodes; the submit button may be below the fold — `input swipe`
to scroll, then re-dump. Image tooling: ImageMagick is `magick` (Windows' `convert.exe` is unrelated).
Use the emulator for visual sign-off of UI work (agents build + run logic tests; orchestrator
installs, launches, screenshots, and confirms rendering before committing).

**Physical phone** (Health Connect / Withings testing) is Android 14+ with platform Health Connect,
same as the emulator. (An older Android 12 phone used the legacy standalone HC app, which
`connect-client 1.1.0` can't drive.)

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
per-day CSV dump → Settings share-sheet via FileProvider). All verified on the emulator
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


**Known bug to fix:** onboarding silently disables "Get started" with no indication of the missing
required field (a user got stuck because **Sex wasn't selected**), and the **Fat % field "(0–1)"** is
confusing (users type `25`). Add validation feedback + fix that field. Note: engine aggregates weight
first-of-log-day (a 2nd same-day weigh-in won't move the trend).
