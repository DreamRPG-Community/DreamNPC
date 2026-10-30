package cn.mythicland.dreamnpc.api;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Public read-only DreamNPC service contract.
 */
public interface DreamNPCApi {

    /**
     * Returns the NPC selected by a player.
     *
     * @param player player
     * @return selected id, or empty when no NPC is selected
     */
    Optional<UUID> selectedNpc(Player player);

    /**
     * Checks whether an NPC exists.
     *
     * @param id stable NPC id
     * @return whether the NPC exists
     */
    boolean exists(UUID id);

    /**
     * Reads detached metadata on the Bukkit main thread.
     *
     * @param id stable NPC identity
     * @return metadata, or empty for an unknown identity
     */
    Optional<NpcOverview> find(UUID id);

}
