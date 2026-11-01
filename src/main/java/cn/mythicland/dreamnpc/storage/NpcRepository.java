package cn.mythicland.dreamnpc.storage;

import cn.mythicland.dreamnpc.model.*;
import cn.mythicland.framework.bukkit.storage.AtomicYamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous YAML repository for DreamNPC definitions.
 */
public final class NpcRepository {

    private final Path target;

    public NpcRepository(Path target) {
        this.target = target.toAbsolutePath().normalize();
    }

    private static Map<UUID, NpcRecord> decode(YamlConfiguration configuration) {
        ConfigurationSection section = configuration.getConfigurationSection("npcs");
        if (section == null) throw new IllegalArgumentException("Missing npcs section");

        Map<UUID, NpcRecord> records = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection npc = section.getConfigurationSection(key);
                if (npc == null) throw new IllegalArgumentException("NPC record must be a mapping");
                for (String coordinate : List.of("x", "y", "z", "yaw", "pitch")) {
                    if ((coordinate.equals("yaw") || coordinate.equals("pitch")) && !npc.contains(coordinate)) continue;
                    if (!(npc.get(coordinate) instanceof Number value) || !Double.isFinite(value.doubleValue()))
                        throw new IllegalArgumentException("Invalid NPC coordinate: " + coordinate);
                }
                NpcLocation location = new NpcLocation(
                        requiredWorldName(npc),
                        npc.getDouble("x"),
                        npc.getDouble("y"),
                        npc.getDouble("z"),
                        (float) npc.getDouble("yaw"),
                        (float) npc.getDouble("pitch")
                );
                List<String> lines = npc.getStringList("name");
                if (lines.isEmpty()) lines = List.of(id.toString());
                List<Double> lineSpacings = npc.getDoubleList("name-line-spacing");

                SkinData skin = decodeSkin(npc.getConfigurationSection("skin"));
                List<BoundCommand> commands = decodeCommands(npc.getMapList("commands"));
                records.put(
                        id,
                        new NpcRecord(
                                id,
                                location,
                                lines,
                                lineSpacings,
                                skin,
                                commands
                        )
                );
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid NPC definition: " + key, exception);
            }
        }
        return Collections.unmodifiableMap(records);
    }

    /**
     * Validates every NPC record without skipping malformed entries or accessing world state.
     *
     * @param configuration parsed editable NPC document
     */
    public static void validateContent(YamlConfiguration configuration) {
        if (!configuration.isConfigurationSection("npcs")) throw new IllegalArgumentException("Missing npcs section");
        decode(configuration);
    }

    private static String requiredWorldName(ConfigurationSection section) {
        String value = section.getString("world");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing world");
        return value.trim();
    }

    private static SkinData decodeSkin(ConfigurationSection section) {
        if (section == null) return null;
        String player = section.getString("player");
        if (player == null || player.isBlank())
            throw new IllegalArgumentException("NPC skin.player requires a premium player name");
        if (section.getBoolean("fixed", false)) {
            return new SkinData(player, section.getString("value"), section.getString("signature"), true);
        }
        return new SkinData(player, null, null);
    }

    private static List<BoundCommand> decodeCommands(List<Map<?, ?>> rawCommands) {
        List<BoundCommand> commands = new ArrayList<>();
        for (Map<?, ?> raw : rawCommands) {
            Object rawMode = raw.get("mode");
            Object rawCommand = raw.get("command");
            if (rawMode == null || rawCommand == null) continue;
            try {
                commands.add(new BoundCommand(
                        CommandExecutionMode.valueOf(rawMode.toString().trim().toUpperCase()),
                        rawCommand.toString()
                ));
            } catch (IllegalArgumentException ignored) {
                // Ignore only the malformed binding; the NPC itself remains usable.
            }
        }
        return List.copyOf(commands);
    }

    private static Map<String, Object> encode(Map<UUID, NpcRecord> records) {
        Map<String, Object> npcValues = new LinkedHashMap<>();
        for (NpcRecord record : records.values()) {
            Map<String, Object> values = new LinkedHashMap<>();
            NpcLocation location = record.location();
            values.put("world", location.worldName());
            values.put("x", location.x());
            values.put("y", location.y());
            values.put("z", location.z());
            values.put("yaw", location.yaw());
            values.put("pitch", location.pitch());
            values.put("name", record.nameLines());
            if (!record.nameLineSpacings().isEmpty()) {
                values.put("name-line-spacing", record.nameLineSpacings());
            }
            if (record.skin() != null) {
                SkinData skin = record.skin();
                values.put("skin", skin.fixed()
                        ? Map.of("player", skin.playerName(), "fixed", true, "value", skin.value(), "signature",
                        skin.signature())
                        : Map.of("player", skin.playerName()));
            }
            List<Map<String, String>> commands = new ArrayList<>();
            for (BoundCommand command : record.commands()) {
                commands.add(Map.of(
                        "mode", command.mode().name().toLowerCase(),
                        "command", command.command()
                ));
            }
            values.put("commands", commands);
            npcValues.put(record.id().toString(), values);
        }
        return Map.of("npcs", npcValues);
    }

    /**
     * Loads a defensive snapshot without touching Bukkit world state.
     *
     * @return future containing valid records
     */
    public CompletableFuture<Map<UUID, NpcRecord>> load() {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.exists(target)) throw new IllegalStateException("NPC content file is missing: " + target);
            try {
                String content = Files.readString(target);
                YamlConfiguration configuration = new YamlConfiguration();
                configuration.load(new StringReader(content));
                return decode(configuration);
            } catch (IOException | InvalidConfigurationException exception) {
                throw new IllegalStateException("Could not load " + target, exception);
            }
        });
    }

    /**
     * Saves a defensive snapshot atomically.
     *
     * @param records records to save
     * @return completion future
     */
    public CompletableFuture<Void> save(Map<UUID, NpcRecord> records) {
        Map<UUID, NpcRecord> snapshot = Map.copyOf(records);
        return CompletableFuture.runAsync(() -> {
            try {
                AtomicYamlStore.write(target, encode(snapshot));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not save " + target, exception);
            }
        });
    }
}
