import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    val mcVersion = libs.versions.minecraft.get()
    val modVersion = libs.versions.mod.version.get()

    version = "$mcVersion-$modVersion"
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation(libs.meteor.client)
    // A separate Fabric mod at development runtime; do not include it in the addon JAR.
    modImplementation(libs.baritone)
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}

// Catch compile-only/API-only Baritone regressions without requiring a graphical client.
val verifyBaritoneRuntime by tasks.registering {
    group = "verification"
    description = "Checks that one complete Baritone mod is on the development runtime classpath."
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    doLast {
        val requiredClasses = listOf(
            "baritone/api/pathing/goals/Goal.class",
            "baritone/command/defaults/ComeCommand.class"
        )
        val providers = runtime.get().files.filter { file ->
            file.extension == "jar" && ZipFile(file).use { zip ->
                requiredClasses.any { zip.getEntry(it) != null }
            }
        }
        check(providers.size == 1) { "Expected one Baritone runtime provider, found: $providers" }
        ZipFile(providers.single()).use { zip ->
            requiredClasses.forEach { check(zip.getEntry(it) != null) { "Missing Baritone class: $it" } }
            val metadata = zip.getEntry("fabric.mod.json")
            check(metadata != null) { "Baritone must be a Fabric mod, not an API-only JAR" }
            val json = zip.getInputStream(metadata).bufferedReader().use { it.readText() }
            check(json.contains("\"baritone-meteor\"")) { "Expected Meteor's Baritone mod" }
        }
    }
}

tasks.check { dependsOn(verifyBaritoneRuntime) }

val verifyDistribution by tasks.registering {
    group = "verification"
    description = "Checks remapped addon metadata, module classes, and dependency separation."
    dependsOn(tasks.remapJar)
    val distribution = tasks.remapJar.flatMap { it.archiveFile }
    val expectedVersion = project.version.toString()
    inputs.file(distribution)
    inputs.property("version", expectedVersion)
    doLast {
        ZipFile(distribution.get().asFile).use { zip ->
            listOf(
                "fabric.mod.json", "rail-builder.mixins.json", "assets/railbuilder/icon.png",
                "dev/killermelon/railbuilder/RailBuilder.class",
                "dev/killermelon/railbuilder/modules/PoweredRailBuilder.class",
                "dev/killermelon/railbuilder/modules/RoofRailBuilder.class"
            ).forEach { check(zip.getEntry(it) != null) { "Missing distribution entry: $it" } }
            val metadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).bufferedReader().use { it.readText() }
            check(!metadata.contains('$' + "{")) { "Unexpanded Fabric metadata" }
            check(metadata.contains("\"${expectedVersion}\"")) { "Incorrect distribution version" }
            check(zip.entries().asSequence().none {
                it.name.startsWith("baritone/") || it.name.startsWith("meteordevelopment/") ||
                    it.name.startsWith("META-INF/jars/")
            }) { "The addon must not bundle Meteor or Baritone" }
        }
    }
}

tasks.check { dependsOn(verifyDistribution) }
