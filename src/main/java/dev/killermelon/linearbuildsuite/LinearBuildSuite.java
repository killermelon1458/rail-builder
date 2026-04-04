package dev.killermelon.linearbuildsuite;

import com.mojang.logging.LogUtils;
import dev.killermelon.linearbuildsuite.modules.PoweredRailBuilder;
import dev.killermelon.linearbuildsuite.modules.RoofRailBuilder;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class LinearBuildSuite extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Linear Build");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Linear Build Suite");

        Modules.get().add(new PoweredRailBuilder());
        Modules.get().add(new RoofRailBuilder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.killermelon.linearbuildsuite";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("killermelon1458", "linear-build-suite");
    }
}