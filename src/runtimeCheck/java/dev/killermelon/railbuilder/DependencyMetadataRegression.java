package dev.killermelon.railbuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Tests Fabric's real predicate semantics, including Minecraft-line prereleases. */
public final class DependencyMetadataRegression {
    public static void main(String[] args) throws Exception {
        JsonObject metadata;
        try (var input = DependencyMetadataRegression.class.getResourceAsStream("/fabric.mod.json")) {
            if (input == null) throw new AssertionError("Missing processed addon metadata");
            metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        if (!metadata.get("id").getAsString().equals("rail-builder")) throw new AssertionError("Wrong addon ID");
        JsonObject depends = metadata.getAsJsonObject("depends");
        if (depends.has("fabric-api") || depends.has("baritone")) throw new AssertionError("Incorrect direct dependency");
        String mc = depends.getAsJsonArray("minecraft").get(0).getAsString();
        check(mc, true, "1.21.11");
        check(mc, false, "1.21.10", "1.21.12", "26.1");
        check(depends.get("java").getAsString(), true, "21", "22", "25");
        check(depends.get("java").getAsString(), false, "17", "20");
        String loader = depends.get("fabricloader").getAsString();
        check(loader, true, "0.18.2", "0.20.1", "0.21.0");
        check(loader, false, "0.18.1", "0.17.0");
        String meteor = depends.get("meteor-client").getAsString();
        check(meteor, true, "1.21.11-37", "1.21.11-86", "1.21.11-100", "1.21.11-SNAPSHOT");
        check(meteor, false, "1.21.11-36", "1.21.10-100", "1.21.12-1", "26.1-1");
        String baritone = depends.get("baritone-meteor").getAsString();
        check(baritone, true, "1.21.11-SNAPSHOT", "1.21.11", "1.21.11-2");
        check(baritone, false, "1.21.10-SNAPSHOT", "1.21.12-SNAPSHOT", "26.1-SNAPSHOT");
        System.out.println("Fabric dependency metadata predicates passed; acceptance is not binary compatibility.");
    }

    private static void check(String requirement, boolean expected, String... versions) throws Exception {
        VersionPredicate predicate = VersionPredicate.parse(requirement);
        for (String version : versions) {
            boolean actual = predicate.test(Version.parse(version));
            if (actual != expected) throw new AssertionError(requirement + " vs " + version + ": " + actual);
        }
    }
}
