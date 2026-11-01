package cn.mythicland.dreamnpc;

import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.dreamnpc.api.DreamNPCApi;
import cn.mythicland.dreamnpc.api.DreamNPCInteractEvent;
import cn.mythicland.dreamnpc.api.DreamNPCRemovedEvent;
import cn.mythicland.dreamnpc.api.NpcOverview;
import cn.mythicland.dreamnpc.model.*;
import cn.mythicland.dreamnpc.runtime.RuntimeNpc;
import cn.mythicland.dreamnpc.storage.NpcRepository;
import cn.mythicland.framework.bukkit.command.VanillaCommandMessages;
import cn.mythicland.framework.bukkit.component.ServiceComponent;
import cn.mythicland.framework.bukkit.config.ConfigSupport;
import cn.mythicland.framework.bukkit.hologram.FloatingTextHandle;
import cn.mythicland.framework.bukkit.hologram.FloatingTextService;
import cn.mythicland.framework.bukkit.hologram.FloatingTextSpec;
import cn.mythicland.framework.bukkit.location.LocationSnapper;
import cn.mythicland.framework.bukkit.task.PluginTaskScope;
import cn.mythicland.npclib.NPCLib;
import cn.mythicland.npclib.api.NPC;
import cn.mythicland.npclib.api.events.NPCInteractEvent;
import cn.mythicland.npclib.api.skin.Skin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Main-thread DreamNPC aggregate and runtime coordinator.
 */
@ServiceComponent(DreamNPCApi.class)
public final class DreamNPCService implements DreamNPCApi {

    private static final String ADMIN_PERMISSION = "dreamnpc.admin";
    private static final long[] VIEWER_REFRESH_DELAYS = {1L, 10L, 20L};
    private static final long VISIBILITY_RECONCILE_INTERVAL_TICKS = 20L;
    private static final double NPC_SELECTION_HEIGHT = 1.8D;
    private static final double RAY_EPSILON = 1.0E-9D;

    private final DreamNPCPlugin plugin;
    private final DreamLibApi lib;
    private final FloatingTextService floatingText;
    private final PluginTaskScope tasks;
    private final NpcRepository repository;
    private final DreamNPCSettings settings;
    private final MojangSkinFetcher skinFetcher = new MojangSkinFetcher();
    private final Map<UUID, NpcRecord> records = new LinkedHashMap<>();
    private final Map<UUID, RuntimeNpc> runtime = new LinkedHashMap<>();
    private final Map<NPC, UUID> npcIds = new IdentityHashMap<>();
    private final Map<UUID, UUID> selections = new LinkedHashMap<>();
    private NPCLib npcLib;
    private CompletableFuture<Void> saveTail = CompletableFuture.completedFuture(null);
    private boolean enabled;
    private boolean loading;

    private static Path npcFile(DreamNPCPlugin plugin) {
        Path file = plugin.getDataFolder().toPath().resolve("npcs.yml");
        if (!Files.exists(file)) plugin.saveResource("npcs.yml", false);
        return file;
    }

    public DreamNPCService(
            DreamNPCPlugin plugin,
            DreamLibApi lib,
            FloatingTextService floatingText,
            PluginTaskScope tasks,
            DreamNPCSettings settings
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lib = Objects.requireNonNull(lib, "lib");
        this.floatingText = Objects.requireNonNull(floatingText, "floatingText");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repository = new NpcRepository(npcFile(plugin));
    }

    /**
     * Returns the first distance where the player's view ray intersects the NPC selection envelope.
     *
     * @param eye         player eye location
     * @param direction   normalized view direction
     * @param npcLocation NPC feet location
     * @param maxDistance maximum selection distance
     * @param radius      horizontal envelope radius and tolerance padding
     * @return hit distance, or positive infinity when the ray misses
     */
    static double selectionDistance(
            Location eye,
            Vector direction,
            Location npcLocation,
            double maxDistance,
            double radius
    ) {
        if (maxDistance <= 0.0D || radius <= 0.0D) return Double.POSITIVE_INFINITY;

        double[] interval = {0.0D, maxDistance};
        if (!clipAxis(interval, eye.getX(), direction.getX(), npcLocation.getX() - radius, npcLocation.getX() + radius)
                || !clipAxis(interval, eye.getY(), direction.getY(), npcLocation.getY() - radius,
                npcLocation.getY() + NPC_SELECTION_HEIGHT + radius)
                || !clipAxis(interval, eye.getZ(), direction.getZ(), npcLocation.getZ() - radius,
                npcLocation.getZ() + radius)) {
            return Double.POSITIVE_INFINITY;
        }
        return interval[0] <= maxDistance ? interval[0] : Double.POSITIVE_INFINITY;
    }

    private static boolean clipAxis(
            double[] interval,
            double origin,
            double direction,
            double min,
            double max
    ) {
        if (Math.abs(direction) <= RAY_EPSILON) return !(origin < min || origin > max);
        double first = (min - origin) / direction;
        double second = (max - origin) / direction;
        interval[0] = Math.clamp(Math.min(first, second), interval[0], Double.POSITIVE_INFINITY);
        interval[1] = Math.clamp(Math.max(first, second), Double.NEGATIVE_INFINITY, interval[1]);
        return !(interval[0] > interval[1]);
    }

    static Location snapFacingPlayer(Location location, Location playerLocation) {
        Location snappedTarget = LocationSnapper.snapBlockCenter(location);
        Location snappedPlayer = LocationSnapper.snapBlockCenter(playerLocation);
        Vector horizontalDirection = snappedPlayer.toVector().subtract(snappedTarget.toVector());
        horizontalDirection.setY(0.0D);
        if (horizontalDirection.lengthSquared() > 1.0E-6D) {
            snappedTarget.setDirection(horizontalDirection);
        }
        snappedTarget.setPitch(0.0F);
        return LocationSnapper.snapBlockAndHorizontalView(snappedTarget);
    }

    static Location snappedHeadTarget(Location npcLocation, Location playerLocation) {
        Location playerBlock = LocationSnapper.snapBlockCenter(playerLocation);
        Vector direction = playerBlock.toVector().subtract(npcLocation.toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() <= 1.0E-6D) {
            playerBlock.setY(npcLocation.getY());
            playerBlock.setPitch(0.0F);
            return playerBlock;
        }

        Location directionView = npcLocation.clone();
        directionView.setDirection(direction);
        Location snappedDirection = LocationSnapper.snapBlockAndHorizontalView(directionView);
        double radians = Math.toRadians(snappedDirection.getYaw());
        Location target = npcLocation.clone().add(
                -Math.sin(radians),
                0.0D,
                Math.cos(radians)
        );
        target.setPitch(0.0F);
        return target;
    }

    /**
     * Computes the top anchor for a name display whose last line starts at the configured offset.
     *
     * <p>{@link FloatingTextSpec} stores lines from top to bottom, while the shared floating-text
     * service places later lines below the anchor. DreamNPC therefore raises the anchor by the
     * sum of the independent gaps so the bottom line remains at {@code nameOffset}.</p>
     *
     * @param npcLocation  NPC feet location
     * @param nameOffset   height of the bottom line above the NPC
     * @param lineSpacings independent gaps in top-to-bottom line order
     * @return defensive copy of the top display anchor
     */
    static Location nameDisplayAnchor(
            Location npcLocation,
            double nameOffset,
            List<Double> lineSpacings
    ) {
        Objects.requireNonNull(lineSpacings, "lineSpacings");
        if (lineSpacings.isEmpty()) throw new IllegalArgumentException("lineSpacings must not be empty");
        double additionalHeight = 0.0D;
        for (int index = 1; index < lineSpacings.size(); index++) {
            additionalHeight += lineSpacings.get(index);
        }
        return Objects.requireNonNull(npcLocation, "npcLocation").clone().add(
                0.0D,
                nameOffset + additionalHeight,
                0.0D
        );
    }

    private static byte toPacketAngle(float angle) {
        return (byte) ((int) (angle * 256.0F / 360.0F));
    }

    private static boolean runtimeRequiresRecreation(NpcRecord previous, NpcRecord current) {
        return !Objects.equals(previous.location(), current.location())
                || !Objects.equals(previous.skin(), current.skin());
    }

    static boolean isStickMaterial(Material material, String configuredMaterial) {
        return material != null
                && configuredMaterial != null
                && material.name().equalsIgnoreCase(configuredMaterial.trim());
    }

    /**
     * Enables the aggregate and starts asynchronous data restoration.
     */
    public void enable() {
        if (enabled) throw new IllegalStateException("DreamNPC is already enabled");
        enabled = true;
        loading = true;
        ensureNpcLib();
        repository.load().whenComplete((loaded, error) -> {
            if (error != null) {
                lib.runOnMain(() -> {
                    loading = false;
                    plugin.getLogger().log(Level.SEVERE, "Could not restore DreamNPC data", error);
                });
                return;
            }
            lib.runOnMain(() -> finishLoad(loaded));
        });
    }

    /**
     * Reloads mutable configuration and applies it to current displays.
     *
     * <p>The persisted NPC definitions are loaded off the primary thread. The resulting records
     * and client-side displays are then replaced on the Bukkit primary thread.</p>
     *
     * @return future completed after the file snapshot has been applied
     */
    public CompletableFuture<Void> reload() {
        ensureEnabled();
        try {
            settings.reload(ConfigSupport.reloadView(plugin));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not reload DreamNPC configuration.", exception);
            return CompletableFuture.failedFuture(exception);
        }
        return reloadNpcData();
    }

    /**
     * Reloads the persisted NPC definitions after Lib has already refreshed plugin configuration.
     *
     * @return future completed after the file snapshot has been applied
     */
    public CompletableFuture<Void> reloadNpcData() {
        ensureEnabled();
        return repository.load()
                .handle(RepositoryLoadResult::new)
                .thenCompose(result -> lib.runOnMain(() -> applyRepositoryReload(result)))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        plugin.getLogger().info("DreamNPC configuration reloaded.");
                    } else {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Could not reload DreamNPC data.",
                                error
                        );
                    }
                });
    }

    /**
     * Stops runtime NPCs and optional integrations.
     */
    public void disable() {
        enabled = false;
        loading = false;
        try {
            saveTail.join();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not finish pending DreamNPC save.", exception);
        }
        for (RuntimeNpc value : List.copyOf(runtime.values())) value.destroy();
        runtime.clear();
        npcIds.clear();
        selections.clear();
        if (npcLib != null) npcLib.shutdown();
        npcLib = null;
    }

    private void finishLoad(Map<UUID, NpcRecord> loaded) {
        if (!enabled) return;
        records.clear();
        records.putAll(loaded);
        resolveConfiguredSkins();
        for (NpcRecord record : records.values()) createRuntimeSafely(record);
        loading = false;
        tasks.runLater(20L, this::createMissingRuntimes);
        scheduleOnlineViewerRefreshes();
        plugin.getLogger().info("Loaded " + records.size() + " DreamNPC definition(s).");
    }

    private void createMissingRuntimes() {
        if (!enabled) return;
        for (NpcRecord record : records.values()) {
            if (!runtime.containsKey(record.id())) createRuntimeSafely(record);
        }
    }

    private void createRuntimeSafely(NpcRecord record) {
        try {
            createRuntime(record);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not create NPC " + record.id(), exception);
        }
    }

    private void createRuntime(NpcRecord record) {
        createRuntime(record, null);
    }

    private void createRuntime(NpcRecord record, Player initialViewer) {
        if (runtime.containsKey(record.id())) return;
        Location location = record.location().resolve();
        if (location == null) {
            plugin.getLogger().fine("Deferring DreamNPC until its world is loaded: " + record.id() + ": "
                    + record.location().worldName());
            return;
        }

        NPC npc = npcLib.createPacketNpc(location, options -> {
            if (record.skin() != null && record.skin().value() != null) {
                SkinData skin = record.skin();
                options.skin(new Skin(skin.value(), skin.signature()));
            }
        });
        try {
            npc.disableFOV();
            List<Double> lineSpacings = lineSpacings(record);
            Location textLocation = nameDisplayAnchor(
                    location,
                    settings.nameOffset(),
                    lineSpacings
            );
            @SuppressWarnings("resource")
            FloatingTextHandle handle = floatingText.show(
                    textLocation,
                    new FloatingTextSpec(record.nameLines(), lineSpacings, settings.viewDistance())
            );
            runtime.put(record.id(), new RuntimeNpc(npc, handle));
            npcIds.put(npc, record.id());
            if (initialViewer != null) ensureShown(npc, initialViewer);
        } catch (RuntimeException exception) {
            if (npc.isCreated()) npc.destroy();
            throw exception;
        }
    }

    private void recreateRuntime(NpcRecord record) {
        destroyRuntime(record.id());
        createRuntimeSafely(record);
        showToOnlinePlayers();
    }

    private void destroyRuntime(UUID id) {
        RuntimeNpc old = runtime.remove(id);
        if (old == null) return;
        npcIds.remove(old.npc());
        old.destroy();
    }

    private void applyRepositoryReload(RepositoryLoadResult result) {
        if (!enabled) return;
        if (result.error() != null) {
            throw new IllegalStateException("Could not reload DreamNPC data", result.error());
        }

        Map<UUID, NpcRecord> previous = new LinkedHashMap<>(records);
        records.clear();
        records.putAll(result.records());
        resolveConfiguredSkins();
        selections.values().removeIf(id -> !records.containsKey(id));

        for (UUID id : previous.keySet()) {
            if (!records.containsKey(id)) {
                destroyRuntime(id);
                Bukkit.getPluginManager().callEvent(new DreamNPCRemovedEvent(id));
            }
        }
        for (NpcRecord record : records.values()) {
            RuntimeNpc current = runtime.get(record.id());
            if (current == null) {
                createRuntimeSafely(record);
                continue;
            }

            NpcRecord old = previous.get(record.id());
            if (old != null && runtimeRequiresRecreation(old, record)) {
                recreateRuntime(record);
            } else {
                updateNameDisplay(record, current);
            }
        }

        showToOnlinePlayers();
    }

    private List<Double> lineSpacings(NpcRecord record) {
        return settings.lineSpacings(record.nameLines().size(), record.nameLineSpacings());
    }

    /**
     * Ensures that a player is registered as a viewer for every loaded DreamNPC.
     *
     * @param player player to refresh
     */
    public void showTo(Player player) {
        if (!enabled || loading || player == null || !player.isOnline()) return;
        for (RuntimeNpc value : runtime.values()) ensureShown(value.npc(), player);
    }

    private void scheduleOnlineViewerRefreshes() {
        for (long delay : VIEWER_REFRESH_DELAYS) tasks.runLater(delay, this::showToOnlinePlayers);
    }

    private void showToOnlinePlayers() {
        if (!enabled || loading) return;
        for (Player player : Bukkit.getOnlinePlayers()) showTo(player);
    }

    private void ensureShown(NPC npc, Player player) {
        if (npc == null || !npc.isCreated() || player == null || !player.isOnline()) return;
        if (npc.getWorld() == null || player.getWorld() == null
                || !npc.getWorld().equals(player.getWorld())) return;
        try {
            if (!npc.isShown(player)) npc.show(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not show DreamNPC " + npc.getUniqueId() + " to " + player.getName(),
                    exception
            );
        }
    }

    @SuppressWarnings("resource")
    private void updateNameDisplay(NpcRecord record, RuntimeNpc value) {
        Location location = record.location().resolve();
        if (location == null) return;
        List<Double> lineSpacings = lineSpacings(record);
        value.nameHandle().update(new FloatingTextSpec(
                record.nameLines(),
                lineSpacings,
                settings.viewDistance()
        ));
        value.nameHandle().move(nameDisplayAnchor(
                location,
                settings.nameOffset(),
                lineSpacings
        ));
    }

    /**
     * Handles a packet-NPC interaction.
     *
     * @param event NPCLib interaction event
     */
    public void handleNpcInteraction(NPCInteractEvent event) {
        if (!enabled || loading) return;
        Player player = event.getWhoClicked();
        UUID npcId = npcIds.get(event.getNPC());
        if (npcId == null) return;

        if (event.getClickType() == NPCInteractEvent.ClickType.RIGHT_CLICK) {
            DreamNPCInteractEvent interaction = new DreamNPCInteractEvent(player, npcId);
            Bukkit.getPluginManager().callEvent(interaction);
            if (interaction.isCancelled()) return;
        }
        if (event.getClickType() == NPCInteractEvent.ClickType.RIGHT_CLICK
                && player.hasPermission(ADMIN_PERMISSION)
                && isStick(player.getInventory().getItemInMainHand().getType())) {
            select(player, npcId);
            return;
        }
        if (event.getClickType() == NPCInteractEvent.ClickType.RIGHT_CLICK) executeCommands(player, npcId);
    }

    /**
     * Selects the NPC nearest to the player's view ray.
     *
     * @param player selecting player
     */
    public void selectByView(Player player) {
        ensureEnabled();
        UUID selected = findByView(player);
        if (selected == null) {
            player.sendMessage(ChatColor.RED + "视线内没有 DreamNPC。");
            return;
        }
        select(player, selected);
    }

    private UUID findByView(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() <= RAY_EPSILON) return null;
        direction.normalize();

        UUID best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NpcRecord record : records.values()) {
            Location location = record.location().resolve();
            if (location == null || !location.getWorld().equals(player.getWorld())) continue;

            double hitDistance = selectionDistance(
                    eye,
                    direction,
                    location,
                    settings.selectionRange(),
                    settings.selectionRadius()
            );
            if (hitDistance < bestDistance) {
                bestDistance = hitDistance;
                best = record.id();
            }
        }
        return best;
    }

    private void select(Player player, UUID id) {
        selections.put(player.getUniqueId(), id);
        player.sendMessage(ChatColor.GREEN + "已选中 DreamNPC " + id + "。");
    }

    /**
     * Moves the selected NPC to the top center of a block and turns it toward the player.
     *
     * @param player administrator
     * @param block  target block
     */
    public void moveSelectedToBlock(Player player, Block block) {
        if (isAdminDenied(player)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        Location target = block.getLocation().add(0.5D, 1.0D, 0.5D);
        Location placement = snapFacingPlayer(target, player);
        move(id, placement);
        RuntimeNpc movedNpc = runtime.get(id);
        if (movedNpc != null) {
            Location horizontalTarget = snappedHeadTarget(movedNpc.npc().getLocation(), player);
            movedNpc.npc().lookAt(horizontalTarget);
        }
        logOrientationDebug(id, player, target, placement);
        player.sendMessage(ChatColor.GREEN + "DreamNPC 已移动到方块顶部。");
    }

    /**
     * Moves the selected NPC to the player's snapped location.
     *
     * @param player administrator
     */
    public void teleportSelected(Player player) {
        if (isAdminDenied(player)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        move(id, player.getLocation().clone());
        player.sendMessage(ChatColor.GREEN + "DreamNPC 已传送到你的当前位置。");
    }

    /**
     * Creates an NPC with one name line per supplied argument.
     *
     * @param player creator
     * @param lines  name lines
     */
    public void create(Player player, List<String> lines) {
        if (isAdminDenied(player)) return;
        if (lines.isEmpty()) {
            player.sendMessage(VanillaCommandMessages.usage("/dreamnpc create <名字...>"));
            return;
        }
        Location location = LocationSnapper.snapBlockAndHorizontalView(player.getLocation());
        UUID id = UUID.randomUUID();
        NpcRecord record = new NpcRecord(
                id,
                NpcLocation.from(location),
                lines,
                settings.defaultNameLineSpacings(lines.size()),
                null,
                List.of()
        );
        records.put(id, record);
        try {
            createRuntime(record, player);
        } catch (RuntimeException exception) {
            records.remove(id);
            throw exception;
        }
        persist();
        player.sendMessage(ChatColor.GREEN + "已创建 DreamNPC " + id + "。");
        select(player, id);
    }

    private Location snapFacingPlayer(Location location, Player player) {
        return snapFacingPlayer(location, player.getLocation());
    }

    private Location snappedHeadTarget(Location npcLocation, Player player) {
        return snappedHeadTarget(npcLocation, player.getLocation());
    }

    /**
     * Renames one line of the selected NPC.
     *
     * @param player administrator
     * @param line   one-based line number
     * @param value  new line text
     */
    public void rename(Player player, int line, String value) {
        if (isAdminDenied(player)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        NpcRecord record = records.get(id);
        if (record == null || line < 1 || line > record.nameLines().size()) {
            player.sendMessage(ChatColor.RED + "名字行号超出范围。");
            return;
        }
        List<String> lines = new ArrayList<>(record.nameLines());
        lines.set(line - 1, value);
        NpcRecord updated = record.withNameLines(lines);
        records.put(id, updated);
        RuntimeNpc runtimeNpc = runtime.get(id);
        if (runtimeNpc != null) updateNameDisplay(updated, runtimeNpc);
        persist();
        player.sendMessage(ChatColor.GREEN + "名字第 " + line + " 行已更新。");
    }

    /**
     * Applies a cached or fetched skin to the selected NPC.
     *
     * @param player     administrator
     * @param playerName player name
     */
    public void skin(Player player, String playerName) {
        if (isAdminDenied(player)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        String normalized = playerName.trim();
        if (normalized.isBlank() || normalized.length() > 16) {
            player.sendMessage(ChatColor.RED + "正版玩家 ID 无效。");
            return;
        }
        NpcRecord record = records.get(id);
        if (record != null && record.skin() != null && record.skin().value() != null &&
                record.skin().playerName().equalsIgnoreCase(normalized)) {
            applySkin(id, record.skin(), player);
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "正在获取玩家皮肤...");
        skinFetcher.fetch(normalized).whenComplete((skin, error) -> lib.runOnMain(() -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + "获取皮肤失败: " + DreamLibApi.rootCauseMessage(error));
                return;
            }
            applySkin(id, skin, player);
        }));
    }

    private void applySkin(UUID id, SkinData skin, Player player) {
        if (!enabled) return;
        NpcRecord record = records.get(id);
        if (record == null) return;
        NpcRecord updated = record.withSkin(skin);
        records.put(id, updated);
        RuntimeNpc value = runtime.get(id);
        if (value != null) {
            value.npc().updateSkin(new Skin(skin.value(), skin.signature()));
        }
        persist();
        player.sendMessage(ChatColor.GREEN + "DreamNPC 皮肤已更新为 " + skin.playerName() + "。");
    }

    private void resolveConfiguredSkins() {
        for (NpcRecord requested : List.copyOf(records.values())) {
            if (requested.skin() == null || requested.skin().fixed()) continue;
            String name = requested.skin().playerName();
            skinFetcher.fetch(name).whenComplete((skin, error) -> lib.runOnMain(() -> {
                NpcRecord current = records.get(requested.id());
                if (!enabled || current == null || current.skin() != requested.skin()) return;
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Cannot resolve NPC skin for premium player " + name, error);
                    return;
                }
                records.put(requested.id(), current.withSkin(skin));
                RuntimeNpc actor = runtime.get(requested.id());
                if (actor != null) actor.npc().updateSkin(new Skin(skin.value(), skin.signature()));
            }));
        }
    }

    public void addCommand(Player player, CommandExecutionMode mode, String command) {
        if (isAdminDenied(player)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        NpcRecord record = records.get(id);
        if (record == null) return;
        List<BoundCommand> commands = new ArrayList<>(record.commands());
        commands.add(new BoundCommand(mode, command));
        records.put(id, record.withCommands(commands));
        persist();
        player.sendMessage(ChatColor.GREEN + "已绑定 " + mode.name().toLowerCase() + " 命令。");
    }

    public void removeCommand(Player player, int index) {
        if (isAdminDenied(player)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        NpcRecord record = records.get(id);
        if (record == null || index < 1 || index > record.commands().size()) {
            player.sendMessage(ChatColor.RED + "命令序号不存在。");
            return;
        }
        List<BoundCommand> commands = new ArrayList<>(record.commands());
        commands.remove(index - 1);
        records.put(id, record.withCommands(commands));
        persist();
        player.sendMessage(ChatColor.GREEN + "已移除第 " + index + " 条命令。");
    }

    public void listCommands(CommandSender sender, Player player) {
        if (isAdminDenied(sender)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        NpcRecord record = records.get(id);
        if (record == null || record.commands().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "当前 NPC 没有绑定命令。");
            return;
        }
        for (int index = 0; index < record.commands().size(); index++) {
            BoundCommand command = record.commands().get(index);
            sender.sendMessage(ChatColor.GRAY + "[" + (index + 1) + "] "
                    + command.mode().name().toLowerCase() + ": " + command.command());
        }
    }

    public void list(CommandSender sender) {
        if (isAdminDenied(sender)) return;
        sender.sendMessage(ChatColor.GREEN + "DreamNPC 数量: " + records.size());
        for (NpcRecord record : records.values()) {
            sender.sendMessage(ChatColor.GRAY.toString() + record.id() + " " + String.join(" / ", record.nameLines())
                    + " @ " + record.location().worldName() + " " + record.location().x() + ","
                    + record.location().y() + "," + record.location().z());
        }
    }

    public void delete(Player player) {
        if (isAdminDenied(player)) return;
        UUID id = selectedId(player);
        if (id == null) return;
        destroyRuntime(id);
        records.remove(id);
        selections.remove(player.getUniqueId());
        persist();
        Bukkit.getPluginManager().callEvent(new DreamNPCRemovedEvent(id));
        player.sendMessage(ChatColor.GREEN + "DreamNPC 已删除。");
    }

    public void executeCommands(Player player, UUID id) {
        NpcRecord record = records.get(id);
        if (record == null) return;
        for (BoundCommand binding : record.commands()) executeCommand(player, binding);
    }

    public int commandCount(Player player) {
        UUID id = selections.get(player.getUniqueId());
        NpcRecord record = id == null ? null : records.get(id);
        return record == null ? 0 : record.commands().size();
    }

    public void clearSelection(Player player) {
        selections.remove(player.getUniqueId());
    }

    private void executeCommand(Player player, BoundCommand binding) {
        String command = binding.command();
        switch (binding.mode()) {
            case PLAYER -> Bukkit.dispatchCommand(player, command);
            case CONSOLE -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            case OP -> {
                boolean wasOp = player.isOp();
                try {
                    player.setOp(true);
                    Bukkit.dispatchCommand(player, command);
                } finally {
                    player.setOp(wasOp);
                }
            }
        }
    }

    private void move(UUID id, Location target) {
        Location snappedTarget = LocationSnapper.snapBlockAndHorizontalView(target);
        logMoveDebug(id, target, snappedTarget);
        applyLocation(id, NpcLocation.from(snappedTarget));
    }

    private void applyLocation(UUID id, NpcLocation location) {
        NpcRecord record = records.get(id);
        if (record == null || record.location().equals(location)) return;
        NpcRecord updated = record.withLocation(location);
        records.put(id, updated);
        recreateRuntime(updated);
        persist();
    }

    private void logMoveDebug(UUID id, Location target, Location snappedTarget) {
        if (!settings.orientationDebug()) return;
        plugin.getLogger().info(String.format(
                Locale.ROOT,
                "[orientation-debug] move npc=%s raw=(%.3f, %.3f, %.3f yaw=%.3f pitch=%.3f) "
                        + "snapped=(%.3f, %.3f, %.3f yaw=%.3f pitch=%.3f)",
                id,
                target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch(),
                snappedTarget.getX(), snappedTarget.getY(), snappedTarget.getZ(),
                snappedTarget.getYaw(), snappedTarget.getPitch()
        ));
    }

    private void logOrientationDebug(UUID id, Player player, Location rawTarget, Location bodyTarget) {
        if (!settings.orientationDebug()) return;

        Location rawFacing = rawTarget.clone();
        Vector rawDirection = player.getLocation().toVector().subtract(rawFacing.toVector());
        rawDirection.setY(0.0D);
        if (rawDirection.lengthSquared() > 1.0E-6D) rawFacing.setDirection(rawDirection);

        Location playerBlock = LocationSnapper.snapBlockCenter(player.getLocation());
        Location snappedFacing = LocationSnapper.snapBlockCenter(rawTarget);
        Vector snappedDirection = playerBlock.toVector().subtract(snappedFacing.toVector());
        snappedDirection.setY(0.0D);
        if (snappedDirection.lengthSquared() > 1.0E-6D) snappedFacing.setDirection(snappedDirection);
        snappedFacing.setPitch(0.0F);
        Location snappedHeadView = LocationSnapper.snapBlockAndHorizontalView(snappedFacing);

        Location lookTarget = snappedHeadTarget(bodyTarget, player);
        Location look = bodyTarget.clone();
        Vector lookDirection = lookTarget.toVector().subtract(look.toVector());
        if (lookDirection.lengthSquared() > 1.0E-6D) look.setDirection(lookDirection);
        look.setPitch(0.0F);

        float placementYaw = bodyTarget.getYaw();
        float lookYaw = look.getYaw();
        plugin.getLogger().info(String.format(
                Locale.ROOT,
                "[orientation-debug] npc=%s player=%s playerPos=(%.3f, %.3f, %.3f) "
                        + "playerBlock=(%.3f, %.3f, %.3f) playerView=(yaw=%.3f pitch=%.3f) "
                        + "rawFacingYaw=%.3f snappedHeadYaw=%.3f "
                        + "spawn=(yaw=%.3f pitch=%.3f byte=%d) "
                        + "look=(yaw=%.3f pitch=%.3f bodyByte=%d headByte=%d) bodyHeadDelta=%.3f",
                id,
                player.getName(),
                player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(),
                playerBlock.getX(), playerBlock.getY(), playerBlock.getZ(),
                player.getLocation().getYaw(), player.getLocation().getPitch(),
                rawFacing.getYaw(), snappedHeadView.getYaw(),
                placementYaw, bodyTarget.getPitch(), toPacketAngle(placementYaw),
                lookYaw, look.getPitch(), toPacketAngle(placementYaw), toPacketAngle(lookYaw),
                LocationSnapper.normalizeYaw(placementYaw - lookYaw)
        ));
    }

    public NpcRecord record(UUID id) {
        return records.get(id);
    }

    public Optional<UUID> selectedNpc(Player player) {
        return Optional.ofNullable(selections.get(player.getUniqueId()));
    }

    boolean isMovementTool(Player player) {
        if (player == null) return false;
        return player.getInventory().getItemInMainHand() != null
                && player.hasPermission(ADMIN_PERMISSION)
                && isStick(player.getInventory().getItemInMainHand().getType());
    }

    @Override
    public boolean exists(UUID id) {
        return records.containsKey(id);
    }

    @Override
    public Optional<NpcOverview> find(UUID id) {
        NpcRecord record = records.get(id);
        if (record == null) return Optional.empty();
        return Optional.of(new NpcOverview(
                id, String.join(" ", record.nameLines()), record.location()
        ));
    }

    private UUID selectedId(Player player) {
        UUID id = selections.get(player.getUniqueId());
        if (id == null || !records.containsKey(id)) return null;
        return id;
    }

    private boolean isAdminDenied(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) return false;
        sender.sendMessage(ChatColor.RED + "你没有执行此命令的权限。");
        return true;
    }

    private boolean isStick(Material material) {
        return isStickMaterial(material, settings.stickMaterial());
    }

    private void persist() {
        Map<UUID, NpcRecord> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(records));
        saveTail = saveTail.handle((ignored, error) -> null)
                .thenCompose(ignored -> repository.save(snapshot))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not persist DreamNPC data", error);
                    return null;
                });
    }

    private void ensureEnabled() {
        if (!enabled) throw new IllegalStateException("DreamNPC service is disabled");
    }

    private void ensureNpcLib() {
        if (npcLib != null) return;
        npcLib = new NPCLib(
                plugin,
                new cn.mythicland.npclib.NPCLibOptions()
                        .setMovementHandling(
                                cn.mythicland.npclib.NPCLibOptions.MovementHandling
                                        .repeatingTask(VISIBILITY_RECONCILE_INTERVAL_TICKS)
                        )
        );
    }

    private record RepositoryLoadResult(Map<UUID, NpcRecord> records, Throwable error) {
    }
}
