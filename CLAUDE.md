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
JDK 17 · Gradle 8.9 (wrapper) · AGP 8.5.2 · Kotlin 2.0.20 · KSP 2.0.20-1.0.25 · Compose BOM
2024.09.00 · Room 2.6.1 · compileSdk/targetSdk **34** (build-tools 34.0.0), minSdk **26** (Health
Connect). Android SDK at `~/Android/Sdk` (`local.properties` sets `sdk.dir`). No system Gradle — use
`./gradlew`. Pin versions in `gradle/libs.versions.toml`.

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
Use the emulator for visual sign-off of UI work (agents build + run logic tests; orchestrator
installs, launches, screenshots, and confirms rendering before committing).

Note: the `~/AudiobookWearOS` project (similar Android/Compose setup) drives **physical** devices
(Pixel 9a USB, Pixel Watch wifi) via `/usr/bin/adb` and `adb-phone.sh`; its `bridge_claude.sh` /
`EmulatorReadme.md` are good references for emulator + adb workflows.

## Status
Spec → scaffold → math engine (`:domain`) → Room data layer → `TdeeRepository` are done, committed,
and tested. Next planned: multi-user seam retrofit, then app plumbing/DI, onboarding, and a minimal
dashboard (see the plan discussed in-session / `outline.md` modules 0 and 5).
