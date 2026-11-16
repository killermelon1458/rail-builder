package railbuilder.compatibility;

import net.fabricmc.api.ClientModInitializer;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Test-only Fabric mod. Never packaged in Rail Builder. Intermediary 1.21.11 names. */
public final class StartupProbe implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Thread monitor = new Thread(() -> {
            try {
                Class<?> clientType = Class.forName("net.minecraft.class_310");
                Object client = clientType.getMethod("method_1551").invoke(null);
                for (int attempt = 0; attempt < 150; attempt++) {
                    CountDownLatch finished = new CountDownLatch(1);
                    ((Executor) client).execute(() -> {
                        try {
                            Object screen = clientType.getField("field_1755").get(client);
                            if (screen == null || !screen.getClass().getName().equals("net.minecraft.class_442")) return;
                            Class<?> modulesType = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
                            Object modules = modulesType.getMethod("get").invoke(null);
                            Collection<?> all = (Collection<?>) modulesType.getMethod("getAll").invoke(modules);
                            for (String simple : new String[] {"PoweredRailBuilder", "RoofRailBuilder"}) {
                                Object module = all.stream().filter(m -> m.getClass().getName().equals(
                                    "dev.killermelon.railbuilder.modules." + simple)).findFirst().orElseThrow();
                                // Resolve method signatures without activating world-dependent operations.
                                module.getClass().getDeclaredMethods();
                                Object settings = module.getClass().getField("settings").get(module);
                                Object debug = settings.getClass().getMethod("get", String.class).invoke(settings, "debug-logging");
                                if (debug == null) throw new AssertionError("Missing debug-logging: " + simple);
                                if (simple.equals("PoweredRailBuilder")) {
                                    Object power = settings.getClass().getMethod("get", String.class).invoke(settings, "power-type");
                                    Object value = power.getClass().getMethod("get").invoke(power);
                                    if (!value.toString().equals("REDSTONE_BLOCK")) throw new AssertionError("Unexpected power default: " + value);
                                }
                                System.out.println("[RailBuilderCompatibility] registered " + simple);
                            }
                            Class.forName("meteordevelopment.meteorclient.utils.player.InvUtils").getDeclaredMethods();
                            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
                            Object provider = api.getMethod("getProvider").invoke(null);
                            Object primary = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
                            if (primary == null) throw new AssertionError("No primary Baritone");
                            Class.forName("baritone.api.pathing.goals.GoalBlock").getConstructor(int.class, int.class, int.class)
                                .newInstance(0, 64, 0);
                            Class.forName("dev.babbaj.pathfinder.NetherPathfinder");
                            System.out.println("[RailBuilderCompatibility] PASS title-screen modules settings inventory-signatures baritone-provider goal pathfinder");
                            clientType.getMethod("method_1592").invoke(client);
                            System.setProperty("railbuilder.compatibility.done", "true");
                        } catch (Throwable failure) {
                            System.err.println("[RailBuilderCompatibility] FAIL");
                            failure.printStackTrace();
                            try { clientType.getMethod("method_1592").invoke(client); } catch (Exception ignored) { }
                            System.setProperty("railbuilder.compatibility.done", "true");
                        } finally {
                            finished.countDown();
                        }
                    });
                    if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Render thread stopped responding");
                    if (System.getProperty("railbuilder.compatibility.done") != null) return;
                    Thread.sleep(1000);
                }
                System.err.println("[RailBuilderCompatibility] FAIL title screen not reached within 150 polls");
            } catch (Throwable failure) {
                System.err.println("[RailBuilderCompatibility] FAIL monitor");
                failure.printStackTrace();
            }
        }, "RailBuilder compatibility probe");
        monitor.setDaemon(true);
        monitor.start();
    }
}
