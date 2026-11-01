package cn.mythicland.dreamnpc.storage;

import cn.mythicland.dreamnpc.model.NpcLocation;
import cn.mythicland.dreamnpc.model.NpcRecord;
import cn.mythicland.dreamnpc.model.SkinData;
import cn.mythicland.dreamnpc.model.BoundCommand;
import cn.mythicland.dreamnpc.model.CommandExecutionMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpcRepositoryTest {

    @Test
    void npcInteractionsPersistAsReadablePlayerCommandBindings() {
        UUID id = UUID.randomUUID();
        var command = new BoundCommand(CommandExecutionMode.PLAYER, "drpg npc " + id);
        var repository = new NpcRepository(temporaryDirectory.resolve("commands.yml"));
        repository.save(Map.of(id, npc(id).withCommands(List.of(command)))).join();
        assertEquals(List.of(command), repository.load().join().get(id).commands());
    }

    @Test
    void persistsOnlyPremiumNameAndResolvesTexturesAtRuntime() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("npcs.yml");
        NpcRepository repository = new NpcRepository(file);
        var record = npc(id).withSkin(new SkinData("Notch", "texture", "signature"));
        repository.save(Map.of(id, record)).join();
        var yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("Notch", yaml.getString("npcs." + id + ".skin.player"));
        assertNull(yaml.get("npcs." + id + ".skin.value"));
        assertNull(yaml.get("npcs." + id + ".skin.signature"));
        var loaded = repository.load().join().get(id);
        assertEquals("Notch", loaded.skin().playerName());
        assertNull(loaded.skin().value());
        yaml.set("npcs." + id + ".skin.value", "old texture");
        yaml.set("npcs." + id + ".skin.signature", "old signature");
        yaml.save(file.toFile());
        assertNull(repository.load().join().get(id).skin().value());
    }

    @Test
    void fixedSignedSkinSurvivesSaveAndLoad() {
        UUID id = UUID.randomUUID();
        NpcRepository repository = new NpcRepository(temporaryDirectory.resolve("fixed-skin.yml"));
        SkinData skin = new SkinData("Notch", "texture", "signature", true);

        repository.save(Map.of(id, npc(id).withSkin(skin))).join();

        assertEquals(skin, repository.load().join().get(id).skin());
    }

    @Test
    void snapsConfiguredYawAndPreservesPitch() {
        UUID id = UUID.randomUUID();
        NpcRepository repository = new NpcRepository(temporaryDirectory.resolve("orientation.yml"));
        NpcRecord record = new NpcRecord(id, new NpcLocation("world", 0.5D, 64D, 0.5D, 133.1503F, -14.4764F),
                List.of("NPC"), null, List.of());

        repository.save(Map.of(id, record)).join();

        NpcLocation location = repository.load().join().get(id).location();
        assertEquals(135.0F, location.yaw());
        assertEquals(-14.4764F, location.pitch());
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void reloadReadsAllNameLinesFromAnExternallyEditedFile() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("npcs.yml");
        Files.writeString(
                file,
                """
                        npcs:
                          %s:
                            world: world
                            x: 11.5
                            y: 7.0
                            z: -23.5
                            yaw: -90.0
                            pitch: 0.0
                            name:
                              - '123'
                              - '234'
                        """.formatted(id)
        );

        NpcRecord loaded = new NpcRepository(file).load().join().get(id);

        assertNotNull(loaded);
        assertEquals(List.of("123", "234"), loaded.nameLines());
    }

    @Test
    void customNameLineSpacingsRoundTrip() {
        UUID id = UUID.randomUUID();
        NpcRecord record = new NpcRecord(
                id,
                new NpcLocation("world", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F),
                List.of("Top", "Middle", "Bottom"),
                List.of(0.35D, 0.6D, 0.0D),
                null,
                List.of()
        );
        NpcRepository repository = new NpcRepository(temporaryDirectory.resolve("npcs.yml"));

        repository.save(Map.of(id, record)).join();

        assertEquals(List.of(0.35D, 0.6D, 0.0D), repository.load().join().get(id).nameLineSpacings());
    }

    @Test
    void malformedSpacingFailsInsteadOfSilentlyRemovingTheNpc() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("npcs.yml");
        Files.writeString(
                file,
                """
                        npcs:
                          %s:
                            world: world
                            x: 0.5
                            y: 64.0
                            z: 0.5
                            name:
                              - 'Top'
                              - 'Bottom'
                            name-line-spacing:
                              - 0.0
                              - 0.35
                        """.formatted(id)
        );

        assertThrows(java.util.concurrent.CompletionException.class, () -> new NpcRepository(file).load().join());
    }

    private static NpcRecord npc(UUID id) {
        return new NpcRecord(
                id,
                new NpcLocation("world", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F),
                List.of("Village", "Merchant"),
                null,
                List.of()
        );
    }
}
