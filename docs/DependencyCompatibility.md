# Minecraft 1.21.11 dependency compatibility

## Metadata policy

Rail Builder 0.1.5 is a Minecraft **1.21.11** binary. Dependency predicates are
loading gates, not a guarantee of compatibility with every future accepted API.
The [Fabric metadata specification](https://docs.fabricmc.net/develop/loader/fabric-mod-json)
describes predicate syntax; `verifyDependencyMetadata` tests the real Loader parser.

| Dependency | Before 0.1.5 | 0.1.5 | Justification |
| --- | --- | --- | --- |
| Minecraft | Exact 1.21.11 | Exact 1.21.11 | Mapped binary target; no other Minecraft version qualified. |
| Java | `>=21` | `>=21` | Production Java 21 class files. |
| Fabric Loader | `>=0.18.2` | `>=0.18.2` | Both tested Meteor builds require this minimum; no upper bound. |
| Meteor Client | `*` | `>=1.21.11-37 <1.21.12-` | Build 37 is the oldest tested build, independently of compilation against build 86. |
| Baritone | Exact `1.21.11-SNAPSHOT` | `baritone-meteor: >=1.21.11- <1.21.12-` | Required direct API; allow compatible labels within the Minecraft line. |
| Fabric API aggregate | Not required directly | Not required directly | No direct addon API usage; isolated startup passed without the aggregate JAR. |

Only one distinct Baritone binary was tested. Meteor builds other than 37 and 86,
future Baritone builds/forks, and other JVM versions/platforms remain unverified.
Builds below Meteor 37 are excluded because they were not qualified, not because a
failure was observed. Do not infer compatibility by relabeling a dependency JAR.

The upper bound `<1.21.12-` excludes prereleases of the next patch. A regression
caught that `~1.21.11-` was too broad: Fabric also accepts `1.21.12-SNAPSHOT` under
that shorthand. Explicit bounds replaced it before the deliverable was built.
Predicate tests cover accepted in-line labels and rejected older, next-line and
26.1 labels. Metadata acceptance is separate from binary/gameplay verification.

## Build versus runtime dependencies

Compilation remains pinned to Minecraft 1.21.11, Yarn 1.21.11+build.3, Loader
0.18.2, Meteor 1.21.11-SNAPSHOT (resolved build 86), Baritone 1.21.11-SNAPSHOT,
Loom 1.14-SNAPSHOT (resolved 1.14.10), Java 21, and NetherPathfinder 1.4.1.
Build coordinates select development artifacts; they are not copied mechanically
into runtime minimum requirements. Yarn and Loom are not installed runtime mods.

Meteor and Baritone are `modImplementation` dependencies needed for compilation
and development startup. Both builder modules reference Baritone APIs even when
pathing is disabled in settings. Neither dependency is bundled in Rail Builder.
Baritone nests NetherPathfinder 1.4.1 for production Fabric loading; the existing
`runtimeOnly` declaration exposes it separately only in Loom development.

Meteor 37 and 86 require Minecraft 1.21.11, Java >=21 and Loader >=0.18.2. Both nest
`fabric-api-base` 1.0.5+4ebb5c083e and `fabric-resource-loader-v1`
1.0.10+78c8b4663e, plus other libraries. Baritone requires Minecraft 1.21.11 and
Loader >=0.14.22, with no standalone Fabric API requirement. Rail Builder has no
direct Fabric API imports and need not duplicate those transitive declarations.
Lowering its Loader minimum would not enable the qualified Meteor stack.

## Tested artifacts and matrix

Snapshot coordinates are mutable. Identify tested binaries by embedded metadata
and hashes, not filenames alone.

| Artifact | Embedded version / identity | SHA-256 |
| --- | --- | --- |
| Meteor build 37 | `1.21.11-37`, commit `6409c29693a8df6428aa8044212fe02f47e3a02f` | `70ceb2edc2a0bf98dc322a2f7a11adfefb7cd6dde594bf34a404056f7c25c7f1` |
| Meteor build 86 | `1.21.11-86`, commit `48030717dc0d4898a195a713fb048bf597fc5b54` | `93390cfdb1030e47f116357ba421c90a9b1ca4832b6551c2dec1ade5eeb42e71` |
| Meteor Baritone | `1.21.11-SNAPSHOT`, Maven `1.21.11-20260103.131549-1` | `3e8c7ab86b7c0148f8711ca2e7cb608712dbc2a97253de0f131ec3db41a2bd3c` |
| Rail Builder | `1.21.11-0.1.5` | `848522ac7178a5d6c7546dd12b2182411dfbc0cfa796193239896d5d3bd6c553` |

The [Baritone snapshot metadata](https://maven.meteordev.org/snapshots/meteordevelopment/baritone/1.21.11-SNAPSHOT/maven-metadata.xml)
advertised build 1 during the audit; no other distinct Baritone build was tested.

All four production-JAR experiments used identical 0.1.5 bytes, Java 21.0.13,
Linux x86_64, a real NVIDIA RTX 2070 SUPER graphical desktop, fresh disposable
profiles, no standalone Fabric API JAR, and the Baritone binary above.

| Minecraft | Loader | Meteor | Baritone | Startup, module registration and normal shutdown | Gameplay |
| --- | --- | --- | --- | --- | --- |
| 1.21.11 | 0.18.2 | 37 | build 1 | Passed | Not tested in matrix |
| 1.21.11 | 0.18.2 | 86 | build 1 | Passed | Not tested in matrix |
| 1.21.11 | 0.20.1 | 37 | build 1 | Passed | Maintainer acceptance reported separately |
| 1.21.11 | 0.20.1 | 86 | build 1 | Passed | Not tested in matrix |
| 1.21.11, named development mappings | 0.18.2 | 86 | build 1 | Main menu visually inspected; Quit Game; Gradle succeeded | Not repeated in audit |
| Other dependency/JVM/platform combinations | — | — | — | Unverified | Unverified |
| Other Minecraft versions, including 26.1 | — | — | — | Metadata rejects; not launched | Unsupported |

Before release preparation, the maintainer reported successful manual v0.1.5
acceptance testing in the installed Fabric/Modrinth profile using Loader 0.20.1
and Meteor 37. This is maintainer-reported acceptance, not an independently
recorded exhaustive gameplay matrix. Earlier focused inventory checks are
recorded in [RedstoneInventoryFix.md](RedstoneInventoryFix.md).

The test-only Fabric probe observes the actual TitleScreen on the render thread,
checks both registered modules and debug settings, the default REDSTONE_BLOCK
power setting, module/inventory method-signature resolution, Baritone's primary
provider, GoalBlock construction and NetherPathfinder initialization, then requests
normal shutdown. It never enables modules, moves inventory or starts pathing.
It is not packaged in Rail Builder. All four experiments returned 0 with:

```text
Initializing Meteor Client
Path Manager: Baritone
Initializing Linear Build Suite
[RailBuilderCompatibility] registered PoweredRailBuilder
[RailBuilderCompatibility] registered RoofRailBuilder
[RailBuilderCompatibility] PASS title-screen modules settings inventory-signatures baritone-provider goal pathfinder
Stopping!
```

No checked linkage failures occurred. Offline Realms authentication warnings were
nonfatal. Signature resolution does not execute gameplay operations.
The deterministic inventory regression passed separately. Removing Meteor or
Baritone in disposable headless fixtures caused actual Fabric resolution to fail
with the correct missing dependency and exit 1. These do not establish that any
untested real build is binary-incompatible. No incompatible installed combination
was discovered. Version rejection assertions are metadata tests only.

## Reproduce the compatibility tests

Use Python 3.11+, JDK 21, Linux x86_64 and a working graphical desktop.

```bash
./gradlew verifyDependencyMetadata verifyInventoryTransfer --no-daemon
./gradlew runClient --no-daemon
# Inspect the main menu and addon, then Quit Game normally.
./gradlew clean build --no-daemon
python3 -m unittest discover -s scripts/tests -v
```

Obtain the actual Meteor and Baritone JARs separately and verify their hashes.
Set `METEOR_JAR` and `BARITONE_JAR` to their absolute paths. Set `CACHE` to the
Gradle user home used for the build. Download official Fabric launch descriptors:

```bash
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}"
AUDIT=$(mktemp -d)
for LOADER in 0.18.2 0.20.1; do
  curl -fLsS --connect-timeout 10 --max-time 40     "https://meta.fabricmc.net/v2/versions/loader/1.21.11/$LOADER/profile/json"     -o "$AUDIT/loader-$LOADER.json"
done
python3 scripts/compatibility/run_profile.py   --minecraft-info "$CACHE/caches/fabric-loom/1.21.11/mojang_minecraft_info.json"   --loader-profile "$AUDIT/loader-0.20.1.json"   --assets "$CACHE/caches/fabric-loom/assets"   --addon build/libs/rail-builder-1.21.11-0.1.5.jar   --meteor "$METEOR_JAR" --baritone "$BARITONE_JAR"   --work-dir "$AUDIT/loader20-meteor37"   --cache "$AUDIT/libraries" --gradle-home "$CACHE"
```

Repeat for each Loader descriptor and Meteor artifact with a new nonexistent
work directory. The runner reuses hash-matching Gradle artifacts, verifies provided
library SHA-1 hashes, downloads official declared libraries with 30-second network
read timeouts, compiles a separate probe with a 30-second limit, and runs the
production binary with a 240-second limit (`--timeout` overrides it). Timeout,
missing markers, nonzero exit or checked linkage failure means failure. Only its
own local Java child is terminated on timeout. An existing work directory is
refused, and no regular launcher profile is modified.

Each experiment retains `client.log`, `profile/logs/latest.log` and `result.json`
with input metadata/hashes, launch arguments, exit status, markers and
`gameplay_validated: false`. Do not commit generated runtime directories or JARs.
For a missing-mod negative test, use another disposable profile, omit exactly
one required mod, and run the recorded command with the new `--gameDir` and
`-Djava.awt.headless=true` before `-cp`. Require nonzero exit and the named missing
dependency. Never remove mods from a regular installation for these tests.

The probe uses 1.21.11 intermediary names. A future 26.1 migration must use a
separate branch, explicit mappings/dependencies and an independent disposable
profile. Audit Meteor/Baritone APIs, slot/action semantics, packets/mixins and
native support, then repeat startup and gameplay checks. Never relabel this
1.21.11 binary as 26.1-compatible.
