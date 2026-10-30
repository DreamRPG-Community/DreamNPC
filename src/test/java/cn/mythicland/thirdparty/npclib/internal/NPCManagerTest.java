package cn.mythicland.thirdparty.npclib.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NPCManagerTest {

    @Test
    void allocatedIdsRemainUniqueWhenAnNpcIsRemoved() {
        int first = NPCManager.allocateEntityId();
        NPCManager.add(null);
        int second = NPCManager.allocateEntityId();
        NPCManager.remove(null);
        int replacement = NPCManager.allocateEntityId();

        assertTrue(first > second && second > replacement);
        assertNotEquals(first, replacement);
        assertNotEquals(second, replacement);
    }
}
