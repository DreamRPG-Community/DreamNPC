package cn.mythicland.dreamnpc;

import cn.mythicland.dreamlib.bootstrap.DreamLibManagedPlugin;


/**
 * Minimal Bukkit entry point for the Lib-managed DreamNPC component graph.
 */
public final class DreamNPCPlugin extends DreamLibManagedPlugin {

    private static final String COMPONENT_PACKAGE = "cn.mythicland.dreamnpc";

    public DreamNPCPlugin() {
        super(COMPONENT_PACKAGE);
    }


}
