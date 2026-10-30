/*
 * Copyright (c) 2018 Jitse Boonstra
 */

package cn.mythicland.thirdparty.npclib.internal;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Jitse Boonstra
 */
public final class NPCManager {

    private static final Set<NPCBase> npcs = new HashSet<>();
    private static final AtomicInteger nextEntityId = new AtomicInteger(Integer.MAX_VALUE);

    private NPCManager() {
        throw new SecurityException("You cannot initialize this class.");
    }

    public static Set<NPCBase> getAllNPCs() {
        return npcs;
    }

    static int allocateEntityId() {
        int id = nextEntityId.getAndUpdate(value -> value > 0 ? value - 1 : 0);
        if (id <= 0) {
            throw new IllegalStateException("NPC entity ID space exhausted");
        }
        return id;
    }

    public static Set<NPCBase> getShownToPlayer(Player player) {
        Set<NPCBase> set = Collections.emptySet();
        for (NPCBase npc : getAllNPCs()) {
            if (npc.getShown().contains(player.getUniqueId())) {
                set.add(npc);
            }
        }
        return set;
    }

    public static void add(NPCBase npc) {
        npcs.add(npc);
    }

    public static void remove(NPCBase npc) {
        npcs.remove(npc);
    }

}
