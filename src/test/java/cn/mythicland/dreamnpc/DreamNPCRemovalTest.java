package cn.mythicland.dreamnpc;

import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.dreamnpc.api.DreamNPCRemovedEvent;
import cn.mythicland.dreamnpc.model.NpcLocation;
import cn.mythicland.dreamnpc.model.NpcRecord;
import cn.mythicland.dreamnpc.storage.NpcRepository;
import cn.mythicland.framework.bukkit.hologram.FloatingTextService;
import cn.mythicland.framework.bukkit.content.ContentWorkspace;
import cn.mythicland.framework.bukkit.task.PluginTaskScope;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CompletionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DreamNPCRemovalTest {
    @TempDir
    Path directory;

    @Test
    void explicitDeletionPublishesTheRemovedIdentityOnceAndPersistsTheRemainingRecords() throws Exception {
        var plugin = mock(DreamNPCPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getName()).thenReturn("DreamNPC");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        UUID npcId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("dreamnpc.admin")).thenReturn(true);
        var plugins = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class);
             var repositories = mockConstruction(NpcRepository.class, (repository, context) ->
                     when(repository.save(anyMap())).thenReturn(CompletableFuture.completedFuture(null)))) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            var lib = mock(DreamLibApi.class);
            var content = new ContentWorkspace(directory);
            when(lib.content()).thenReturn(content);
            Path file = directory.resolve("npcs.yml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "npcs: {}\n");
            var service = new DreamNPCService(plugin, lib, mock(FloatingTextService.class),
                    mock(PluginTaskScope.class), mock(DreamNPCSettings.class));
            entries(service, "records").put(npcId, new NpcRecord(npcId,
                    new NpcLocation("main_city", 0, 1, 0, 0, 0), List.of("NPC"), null, List.of()));
            entries(service, "selections").put(playerId, npcId);
            doAnswer(call -> {
                DreamNPCRemovedEvent event = call.getArgument(0);
                assertEquals(npcId, event.getNpcId());
                assertFalse(service.exists(npcId), "Removal notification must follow the record removal");
                return null;
            }).when(plugins).callEvent(any(DreamNPCRemovedEvent.class));
            service.delete(player);
            service.delete(player);
            verify(plugins, times(1)).callEvent(any(DreamNPCRemovedEvent.class));
            verify(repositories.constructed().getFirst()).save(Map.of());
            assertTrue(service.selectedNpc(player).isEmpty());
        }
    }

    @Test
    void invalidOrMissingNpcDocumentsCannotTurnReloadIntoDeletion() throws Exception {
        var plugin = mock(DreamNPCPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getName()).thenReturn("DreamNPC");
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        var lib = mock(DreamLibApi.class);
        var content = new ContentWorkspace(directory);
        when(lib.content()).thenReturn(content);
        when(lib.runOnMain(any())).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        });
        var manager = mock(PluginManager.class);
        UUID npcId = UUID.randomUUID();
        var original = new NpcRecord(npcId, new NpcLocation("main_city", 1, 2, 3, 0, 0),
                List.of("Keep this NPC and its projects"), null, List.of());
        Path file = directory.resolve("npcs.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "npcs:\n  " + npcId + ": {world: main_city, x: 1, y: 2, z: 3}\n");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            var service = new DreamNPCService(plugin, lib, mock(FloatingTextService.class),
                    mock(PluginTaskScope.class), mock(DreamNPCSettings.class));
            entries(service, "records").put(npcId, original);
            var enabled = DreamNPCService.class.getDeclaredField("enabled");
            enabled.setAccessible(true);
            enabled.setBoolean(service, true);
            for (String invalid : List.of(
                    "npcs:\n  " + npcId + ": broken\n",
                    "npcs:\n  " + npcId + ": {world: main_city, x: wrong, y: 2, z: 3}\n",
                    "npcs:\n  " + npcId + ": {world: main_city, y: 2, z: 3}\n",
                    "not-npcs: {}\n",
                    ""
            )) {
                Files.writeString(file, invalid);
                assertThrows(CompletionException.class, () -> service.reloadNpcData().join(),
                        "Invalid content must fail reload before records are replaced");
                assertEquals(original, service.record(npcId), "A malformed record must not be treated as deletion");
            }
            Files.delete(file);
            assertThrows(CompletionException.class, () -> service.reloadNpcData().join(),
                    "A temporarily moved NPC file must not remove its records or RPG projects");
            assertEquals(original, service.record(npcId));
            verify(manager, never()).callEvent(any(DreamNPCRemovedEvent.class));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> entries(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(owner);
    }

    @Test
    void removalEventRequiresIdentityAndHasTheBukkitHandlerContract() {
        UUID npc = UUID.randomUUID();
        var event = new DreamNPCRemovedEvent(npc);
        assertEquals(npc, event.getNpcId());
        assertSame(DreamNPCRemovedEvent.getHandlerList(), event.getHandlers());
        assertFalse(event.isAsynchronous());
        assertThrows(NullPointerException.class, () -> new DreamNPCRemovedEvent(null));
    }
}
