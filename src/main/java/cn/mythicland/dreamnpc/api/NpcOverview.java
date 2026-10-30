package cn.mythicland.dreamnpc.api;

import cn.mythicland.dreamnpc.model.NpcLocation;

import java.util.UUID;

/**
 * Immutable NPC metadata for administrator directories; never exposes the mutable runtime actor.
 *
 * @param id       stable NPC identity
 * @param name     joined floating display name
 * @param position configured position, including unloaded worlds
 */
public record NpcOverview(UUID id, String name, NpcLocation position) {
}
