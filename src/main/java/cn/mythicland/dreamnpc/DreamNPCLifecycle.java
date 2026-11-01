package cn.mythicland.dreamnpc;

import cn.mythicland.framework.component.LifecycleComponent;
import cn.mythicland.framework.component.ManagedLifecycle;

import java.util.Objects;

/**
 * Owns DreamNPC startup, reload, and shutdown ordering.
 */
@LifecycleComponent
public final class DreamNPCLifecycle implements ManagedLifecycle {

    private final DreamNPCService service;

    public DreamNPCLifecycle(DreamNPCService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void load() {
        service.enable();
    }

    @Override
    public void reload() {
        service.reloadNpcData();
    }

    @Override
    public void unload() {
        service.disable();
    }
}
