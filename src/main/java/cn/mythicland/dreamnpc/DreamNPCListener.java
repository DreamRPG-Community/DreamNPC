package cn.mythicland.dreamnpc;

import cn.mythicland.dreamlib.bootstrap.PluginTaskScope;
import cn.mythicland.dreamlib.bootstrap.annotation.ListenerComponent;
import cn.mythicland.npclib.api.events.NPCInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Objects;

/**
 * Bridges Bukkit and NPCLib interactions into the DreamNPC service.
 */
@ListenerComponent
public final class DreamNPCListener implements Listener {

    private final DreamNPCService service;
    private final PluginTaskScope tasks;

    public DreamNPCListener(DreamNPCService service, PluginTaskScope tasks) {
        this.service = Objects.requireNonNull(service, "service");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @EventHandler
    public void onNpcInteract(NPCInteractEvent event) {
        service.handleNpcInteraction(event);
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || !event.getPlayer().isSneaking()
                || !service.isMovementTool(event.getPlayer())
                || event.getClickedBlock() == null) return;
        if (service.selectedNpc(event.getPlayer()).isPresent()) event.setCancelled(true);
        service.moveSelectedToBlock(event.getPlayer(), event.getClickedBlock());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.clearSelection(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleViewerRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        scheduleViewerRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        scheduleViewerRefresh(event.getPlayer());
    }

    private void scheduleViewerRefresh(Player player) {
        tasks.runLater(10L, () -> service.showTo(player));
    }
}
