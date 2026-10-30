package cn.mythicland.dreamnpc;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DreamNPCServiceInteractionTest {

    @Test
    void movementToolAcceptsConfiguredStickCaseInsensitively() {
        assertTrue(DreamNPCService.isStickMaterial(Material.STICK, "stick"));
        assertTrue(DreamNPCService.isStickMaterial(Material.STICK, " STICK "));
    }

    @Test
    void movementToolRejectsOtherMaterialsAndMissingConfiguration() {
        assertFalse(DreamNPCService.isStickMaterial(Material.BLAZE_ROD, "STICK"));
        assertFalse(DreamNPCService.isStickMaterial(Material.STICK, null));
        assertFalse(DreamNPCService.isStickMaterial(null, "STICK"));
    }
}
