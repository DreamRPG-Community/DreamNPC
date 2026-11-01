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

    /**
     * Reloads NPC definitions without reading shared display settings.
     *
     * @return completion after NPC changes have been applied on the main thread
     */
    public java.util.concurrent.CompletableFuture<Void> reloadContent() {
        return bootstrap(COMPONENT_PACKAGE).resolve(DreamNPCService.class).reloadNpcData();
    }

}
