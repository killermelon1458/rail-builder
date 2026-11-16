package dev.killermelon.railbuilder;

import java.util.Collections;

/** Standalone check: no game window needed, but class initialization/native loading is real. */
public final class NetherPathfinderRuntimeCheck {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = NetherPathfinderRuntimeCheck.class.getClassLoader();
        String resource = "dev/babbaj/pathfinder/NetherPathfinder.class";
        var providers = Collections.list(loader.getResources(resource));
        if (providers.size() != 1) {
            throw new IllegalStateException("Expected one NetherPathfinder runtime provider: " + providers);
        }
        Class<?> pathfinder = Class.forName("dev.babbaj.pathfinder.NetherPathfinder", true, loader);
        if (!Boolean.TRUE.equals(pathfinder.getMethod("isThisSystemSupported").invoke(null))) {
            throw new IllegalStateException("NetherPathfinder native library is unavailable on this system");
        }
        // Exercise JNI as well as loading the class and native library.
        long context = (long) pathfinder.getMethod("newContext", long.class).invoke(null, 0L);
        if (context == 0) throw new IllegalStateException("NetherPathfinder returned a null context");
        pathfinder.getMethod("freeContext", long.class).invoke(null, context);
        System.out.println("NetherPathfinder runtime and JNI initialization passed");
    }
}
