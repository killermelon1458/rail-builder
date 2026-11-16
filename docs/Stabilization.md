# Minecraft 1.21.11 stabilization

## Runtime fixes

Baritone was originally `compileOnly`, so compilation could succeed while Loom's
development runtime lacked `baritone.api.pathing.goals.Goal` and
`baritone.command.defaults.ComeCommand`. Meteor does not supply a full Baritone
mod transitively. `modImplementation(libs.baritone)` now makes exactly one complete
Meteor Baritone mod available to the development client. Production users install
it separately; the addon JAR does not bundle Meteor or Baritone.

Baritone nests `nether-pathfinder-1.4.1.jar`, but its POM does not declare that
library and Loom did not expose the nested copy. A graphical startup reproduced
`NoClassDefFoundError: dev/babbaj/pathfinder/NetherPathfinder`.
The existing `runtimeOnly(dev.babbaj:nether-pathfinder:1.4.1)` declaration supplies
it in development from the group-filtered `https://babbaj.github.io/maven/`
repository. Production Fabric loads Baritone's nested copy. Do not install a
second Baritone or add another Pathfinder JAR to a normal profile.

`verifyBaritoneRuntime` checks for exactly one complete mod provider, including
Goal and ComeCommand. `verifyPathfinderRuntime` loads the native library and
allocates/frees a JNI context. `verifyDistribution` verifies addon metadata and
module classes and rejects embedded dependency copies. These checks run in
`check`/`build`; supported native platforms include Linux x86_64 used by CI.
They do not establish graphical startup or gameplay compatibility.

Other stabilization fixes rename the template project, remove conflicting unused
version properties, track required source assets, verify the wrapper checksum,
and restore automated Java 21 clean builds on pushes and pull requests.
Snapshot publication is a separate manual workflow with write permissions only
on its publishing job. Routine CI never publishes a release.

## Local verification workflow

Use Java 21 and a graphical desktop with working OpenGL. On Linux, use the
existing desktop's DISPLAY and XAUTHORITY rather than substituting a virtual
framebuffer when a real display is available.

1. Run `./gradlew verifyDependencyMetadata verifyInventoryTransfer --no-daemon`.
2. Run `./gradlew runClient --no-daemon`. Confirm the main menu, Meteor and addon
   initialization, and one Baritone mod. In Meteor's Linear Build category,
   verify PoweredRailBuilder and RoofRailBuilder. Inspect `run/logs/latest.log`.
3. Quit Game normally. A crash or timeout is not successful startup.
4. Run `./gradlew clean build --no-daemon`. Check the remapped production JAR's
   embedded metadata and SHA-256 against the release verification record.
5. Test the production JAR in a separate Fabric 1.21.11 profile with the required
   Meteor/Baritone dependencies. Keep exactly one active Rail Builder JAR.

For fresh dependency resolution, use a fresh source checkout and writable,
initially empty `GRADLE_USER_HOME`, then run
`./gradlew clean build --no-daemon --refresh-dependencies`.
Snapshots may change upstream; consult the [artifact hashes and compatibility
matrix](DependencyCompatibility.md) before attributing a change to Rail Builder.

## Gameplay checks

Use a disposable world and preserve backups of existing saved jobs.

* Powered builder: mixed/all-powered rails, both power sources, power spacing,
  floor/shell, lights, liquids and falling blocks, resource exhaustion/recovery.
* Inventory: preferred slot, another hotbar slot, main inventory, occupied/bundle
  destination, cursor preservation, full inventory, and server synchronization.
* Roof builder: nether-roof construction, Baritone movement/cancellation, shulker
  restocking, and safe empty/full-inventory behavior.
* Recovery: disconnect/reconnect and restart, saved-job reload and completion.

See [inventory diagnostics](InventoryDiagnostics.md), the
[confirmed inventory fix](RedstoneInventoryFix.md), and the
[v0.1.5 release verification record](releases/v0.1.5-verification.md).
