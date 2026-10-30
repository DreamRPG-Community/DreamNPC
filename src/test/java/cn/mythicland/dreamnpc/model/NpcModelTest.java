package cn.mythicland.dreamnpc.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpcModelTest {

    @Test
    void boundCommandNormalizesSlashAndWhitespace() {
        BoundCommand command = new BoundCommand(CommandExecutionMode.PLAYER, "  /spawn town  ");

        assertEquals("spawn town", command.command());
    }

    @Test
    void blankBoundCommandIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundCommand(CommandExecutionMode.CONSOLE, " / ")
        );
    }

    @Test
    void npcRecordCopiesMutableCollections() {
        List<String> names = new ArrayList<>(List.of("Guard", "Welcome"));
        List<BoundCommand> commands = new ArrayList<>(List.of(
                new BoundCommand(CommandExecutionMode.OP, "give %player% diamond")
        ));
        NpcRecord record = new NpcRecord(
                UUID.randomUUID(),
                new NpcLocation("world", 1.5D, 64.0D, -2.5D, 90.0F, 0.0F),
                names,
                null,
                commands
        );

        names.add("ignored");
        commands.clear();

        assertEquals(List.of("Guard", "Welcome"), record.nameLines());
        assertEquals(1, record.commands().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> record.nameLines().add("ignored")
        );
    }

    @Test
    void npcRecordKeepsCustomNameLineSpacingsImmutable() {
        List<Double> spacings = new ArrayList<>(List.of(0.35D, 0.6D, 0.0D));
        NpcRecord record = new NpcRecord(
                UUID.randomUUID(),
                new NpcLocation("world", 1.5D, 64.0D, -2.5D, 90.0F, 0.0F),
                List.of("Top", "Middle", "Bottom"),
                spacings,
                null,
                List.of()
        );

        spacings.set(1, 2.0D);

        assertEquals(List.of(0.35D, 0.6D, 0.0D), record.nameLineSpacings());
        assertThrows(
                UnsupportedOperationException.class,
                () -> record.nameLineSpacings().add(1.0D)
        );
    }

    @Test
    void npcRecordRejectsLegacyRuntimeSpacingOrder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NpcRecord(
                        UUID.randomUUID(),
                        new NpcLocation("world", 1.5D, 64.0D, -2.5D, 90.0F, 0.0F),
                        List.of("Top", "Bottom"),
                        List.of(0.0D, 0.35D),
                        null,
                        List.of()
                )
        );
    }

    @Test
    void invalidNpcLocationIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NpcLocation("world", Double.NaN, 64.0D, 0.0D, 0.0F, 0.0F)
        );
    }

}
