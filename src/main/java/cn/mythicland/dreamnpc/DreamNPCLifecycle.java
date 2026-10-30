package cn.mythicland.dreamnpc;

import cn.mythicland.dreamlib.bootstrap.DreamLibPluginLifecycle;
import cn.mythicland.dreamlib.bootstrap.annotation.LifecycleComponent;

import java.util.Objects;

/**
 * Owns DreamNPC startup, reload, and shutdown ordering.
 */
@LifecycleComponent
public final class DreamNPCLifecycle implements DreamLibPluginLifecycle {

    private final DreamNPCService service;

    public DreamNPCLifecycle(DreamNPCService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void enable() {
        service.enable();
    }

    @Override
    public void reload() {
        service.reloadNpcData();
    }

    @Override
    public void disable() {
        service.disable();
    }
}
