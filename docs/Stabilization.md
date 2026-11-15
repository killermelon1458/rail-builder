# 1.21.11 stabilization / proposed v0.1.2

## Diagnosis

Baseline: `bdb0eb1` on main. The historical `814b6f5` changed the development
release trigger from push to manual dispatch. Eight April 2026 development runs
are recorded as failed; GitHub returns HTTP 410 for their expired logs and retains
no job steps, so their individual failure causes cannot be established.

The current Baritone declaration was `compileOnly`. That makes the API available
to the compiler but excludes Baritone from Loom's development runtime. Meteor's
published 1.21.11 JAR does not bundle or transitively supply Baritone. It contains
a mixin targeting `baritone.command.defaults.ComeCommand`; the addon directly uses
Baritone's API. A normal installation with a separate compatible Baritone mod can
work while the development client lacks those classes.

The baseline client reproduced the missing ComeCommand target warning. The
historical `NoClassDefFoundError` for Goal was not reached: this host fails GLFW
initialization before addon initialization. The baseline runtime classpath lacked
Baritone, and the matching Baritone JAR contains both historical missing classes.

The fix is `modImplementation(libs.baritone)` with an explicit
`1.21.11-SNAPSHOT` catalog entry. Loom remaps and loads it as a separate development
mod. No `include` dependency is used; the production addon contains no Baritone or
Meteor classes/nested JARs. Fabric metadata now requires `baritone-meteor` and
Fabric Loader, producing a clear dependency error for incomplete installations.
The older 1.21.10 Baritone coordinate in repository history is not restored.

Other confirmed configuration problems: the template project name, conflicting
unused version properties, broad source-asset ignore rules, and manual-only push
verification. These are corrected without gameplay Java changes. `gradlew` was
already tracked executable (`100755`); the wrapper remains Gradle 9.2.0 with its
published distribution SHA-256 added. Minecraft, Yarn, Loader, Meteor, Loom, and
Java targets are preserved.

## Desktop startup and production verification (required before release)

1. Use JDK 21 and a desktop with OpenGL. Start from a fresh clone and run
   `./gradlew clean build --no-daemon --refresh-dependencies`, then
   `./gradlew runClient`. Leave `run/mods` empty of manually installed dependencies.
2. Reach the title screen. Check `run/logs/latest.log` lists Minecraft 1.21.11,
   Fabric Loader 0.18.2, Meteor, Rail Builder, and **one** baritone-meteor mod.
   Confirm no missing Goal/ComeCommand errors and that
   `Initializing Linear Build Suite` appears.
3. Open Meteor's GUI and confirm **PoweredRailBuilder** and **RoofRailBuilder**
   under **Linear Build**. Join a disposable singleplayer world and confirm both
   can be enabled/disabled without errors. Confirm Baritone movement works.
4. Repeat with `build/libs/rail-builder-1.21.11-0.1.2.jar` in a clean normal Fabric
   profile with the separate matching Meteor and Baritone JARs. Do not use the
   development JAR or install duplicate Baritone distributions.
5. PoweredRailBuilder: build a short tunnel with mixed and all-powered rail
   settings; verify power spacing, movement, floor/shell, lights, liquid and
   falling-block handling. Exhaust a required item and verify safe pausing and
   recovery when materials return.
6. RoofRailBuilder: build a short nether-roof line; verify powered placement,
   Baritone movement, shulker restocking, and safe behavior with full/empty
   inventory. Disconnect/reconnect and restart the client; verify saved job
   recovery and completion. Preserve a backup of existing saved jobs and confirm
   they load without migration.

This host has no DISPLAY or Xvfb. Both startup attempts fail with:
`IllegalStateException: Failed to initialize GLFW ... Failed to detect any supported platform`.
The subsequent `Can't getDevice() before it was initialized` is crash-report
collection fallout. Fixed startup lists Baritone and removes the ComeCommand
warning, but does **not** verify title screen, addon initialization, module
registration, or gameplay. It also reports a nonfatal LocalPlayer class warning
from dependencies; desktop verification must check whether any later failure
follows it. CI deliberately does not claim graphical startup verification.

## Release gate

Review the PR and complete the desktop and production-profile checklist before
tagging **v0.1.2**. No tag or release is created by this stabilization task.
Upstream SNAPSHOT coordinates remain mutable as required; clean-cache resolution
is tested, but future upstream changes can affect reproducibility. Routine CI
runs a clean build on every push and PR, including runtime and distribution
verification, and uploads the remapped addon. Snapshot publication is manual and
uses write permission only in its separate publishing job.

## Recorded validation (2026-11-15)

All Gradle runs used Java 21.0.13. The sandbox's default Gradle home was read-only
and network access was restricted; successful runs used temporary writable Gradle
homes with network access authorized. Full command output was captured in `/tmp`.

| Check | Result |
| --- | --- |
| Baseline `clean build --no-daemon --refresh-dependencies` | Passed using an empty `/tmp/rail-gradle` home; log `/tmp/rail-baseline-build-network.log` |
| Baseline `runClient --no-daemon` | Failed GLFW initialization; missing ComeCommand warning; full log `/tmp/rail-baseline-client-network.log` |
| Fixed `clean build --no-daemon` | Passed, including runtime verification; `/tmp/rail-fixed-build.log` |
| Fixed `runClient --no-daemon` | Loaded Baritone, Meteor and addon metadata; no ComeCommand warning; failed GLFW; `/tmp/rail-fixed-client.log` |
| Final `clean build --no-daemon --warning-mode all` | Passed both verification tasks with no Gradle deprecation warning; `/tmp/rail-final-build.log` |
| Clean source copy, empty `/tmp/rail-fresh-gradle`, `clean build --no-daemon --refresh-dependencies` | Passed; `/tmp/rail-fresh-build.log` |
| `dependencies --configuration runtimeClasspath --no-daemon` | Passed; one remapped Baritone 1.21.11-SNAPSHOT; `/tmp/rail-runtime-dependencies.log` |
| Temporary copy reverting to compile-only, `verifyBaritoneRuntime --no-daemon --offline` | Failed as expected: no runtime provider; `/tmp/rail-negative-runtime.log` |
| `help --no-daemon --offline --warning-mode all` | Passed; `/tmp/rail-gradle-warnings.log` |
| actionlint 1.7.7, both workflows | Passed |
| `git diff --check` | Passed |

The clean-cache run resolved Loom 1.14.10 through the required 1.14-SNAPSHOT
coordinate, Meteor snapshot build 86 (`20260810.140237-86`), and Baritone
`20260103.131549-1`. Loom emits upstream remapping warnings about
`onEntityCollision`/`method_9548`; compilation and remapping finish successfully.
No automated gameplay tests are present. Module classes and source registration
are checked/inspected, but actual registration awaits desktop verification.
