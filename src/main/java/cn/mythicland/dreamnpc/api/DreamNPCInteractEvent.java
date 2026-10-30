package cn.mythicland.dreamnpc.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired on the main thread before a managed NPC runs its right-click commands.
 */
public final class DreamNPCInteractEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID npcId;
    private boolean cancelled;

    /**
     * Creates an NPC interaction event.
     *
     * @param player the player interacting with the NPC
     * @param npcId  the stable NPC identifier
     */
    public DreamNPCInteractEvent(Player player, UUID npcId) {
        this.player = Objects.requireNonNull(player, "player");
        this.npcId = Objects.requireNonNull(npcId, "npcId");
    }

    /**
     * @return the player interacting with the NPC
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the stable NPC identifier
     */
    public UUID getNpcId() {
        return npcId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return Bukkit's event handler registry
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
