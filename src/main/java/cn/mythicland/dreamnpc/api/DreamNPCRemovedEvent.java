package cn.mythicland.dreamnpc.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

/**
 * Reports an explicit NPC deletion or removal by a successful configuration reload.
 * Fired synchronously on the Bukkit main thread after the record is removed. Runtime
 * despawning, world unloading and plugin shutdown do not represent record removal.
 */
public final class DreamNPCRemovedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID npcId;

    /**
     * Creates a removal notification on the main thread.
     *
     * @param npcId stable identifier of the removed record
     * @throws NullPointerException when npcId is null
     */
    public DreamNPCRemovedEvent(UUID npcId) {
        this.npcId = Objects.requireNonNull(npcId, "npcId");
    }

    /** @return stable identifier of the removed record */
    public UUID getNpcId() {
        return npcId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** @return Bukkit handler registry for record removal notifications */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
