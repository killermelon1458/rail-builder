package dev.killermelon.railbuilder;

import com.mojang.logging.LogUtils;
import dev.killermelon.railbuilder.modules.PoweredRailBuilder;
import dev.killermelon.railbuilder.modules.RoofRailBuilder;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class RailBuilder extends MeteorAddon {
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
        return "dev.killermelon.railbuilder";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("killermelon1458", "rail-builder");
    }
}
