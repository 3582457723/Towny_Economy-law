package dev.townyeconomylaw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateTest {
    private static final Logger LOGGER = Logger.getLogger(StateTest.class.getName());

    @TempDir
    Path directory;

    @Test
    void committedDataSurvivesOpeningTheStoreAgain() throws Exception {
        State state = new State(directory, LOGGER);
        state.transaction(undo -> state.data().set("balances.account", 125L));

        State reopened = new State(directory, LOGGER);
        assertEquals(125, reopened.data().getLong("balances.account"));
    }

    @Test
    void failedDiskWriteRestoresStateAndCompensatesExternalDebit() throws Exception {
        State state = new State(directory, LOGGER);
        Files.createDirectory(directory.resolve("state.yml"));
        AtomicLong externalBalance = new AtomicLong(500);

        assertThrows(IllegalStateException.class, () -> state.transaction(undo -> {
            externalBalance.addAndGet(-100);
            undo.push(() -> externalBalance.addAndGet(100));
            state.data().set("balances.account", 100L);
        }));

        assertEquals(0, state.data().getLong("balances.account"));
        assertEquals(500, externalBalance.get());
    }
}
