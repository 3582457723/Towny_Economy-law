package dev.townyeconomylaw;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class State {
    private final Path file;
    private final Logger logger;
    private YamlConfiguration data = new YamlConfiguration();
    private boolean writable = true;

    State(Path directory, Logger logger) throws IOException, InvalidConfigurationException {
        Files.createDirectories(directory);
        this.file = directory.resolve("state.yml");
        this.logger = logger;
        if (Files.exists(file)) data.load(file.toFile());
    }

    YamlConfiguration data() {
        return data;
    }

    Set<String> keys(String path) {
        ConfigurationSection section = data.getConfigurationSection(path);
        return section == null ? Set.of() : section.getKeys(false);
    }

    void transaction(Change change) {
        require(writable, "状態の書き込みが停止しています。管理者に連絡してください。");
        String before = data.saveToString();
        ArrayDeque<Runnable> undo = new ArrayDeque<>();
        try {
            change.apply(undo);
            save();
        } catch (IOException | RuntimeException failure) {
            try {
                data.loadFromString(before);
            } catch (InvalidConfigurationException invalid) {
                writable = false;
                logger.log(Level.SEVERE, "Cannot restore transaction snapshot", invalid);
            }
            for (Runnable rollback : undo) {
                try {
                    rollback.run();
                } catch (RuntimeException broken) {
                    writable = false;
                    logger.log(Level.SEVERE, "Transaction compensation failed", broken);
                }
            }
            if (failure instanceof Rejected rejected) throw rejected;
            throw new IllegalStateException("Transaction failed", failure);
        }
    }

    private void save() throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "state-", ".tmp");
        try {
            Files.writeString(temp, data.saveToString());
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            if (Files.exists(temp)) Files.delete(temp);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new Rejected(message);
    }

    interface Change {
        void apply(ArrayDeque<Runnable> undo);
    }

    static final class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message);
        }
    }
}
