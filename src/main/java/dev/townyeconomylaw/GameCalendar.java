package dev.townyeconomylaw;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

final class GameCalendar implements Listener {
    enum Season {
        SPRING("春"), SUMMER("夏"), AUTUMN("秋"), WINTER("冬");

        final String label;

        Season(String label) {
            this.label = label;
        }
    }

    record Date(long year, int month, int day, Season season) {}

    private static final long TICKS_PER_DAY = 24_000;
    private static final int DAYS_PER_MONTH = 30;
    private static final int MONTHS_PER_YEAR = 12;
    private static final int DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR;
    private static final String ROOT = "game-calendar.";

    private record Clock(long raw, long elapsed) {}

    private final State state;
    private final Map<UUID, Clock> clocks = new HashMap<>();

    GameCalendar(State state) {
        this.state = state;
    }

    void start() {
        for (World world : Bukkit.getWorlds()) sample(world);
        save(true);
    }

    long elapsedTicks(World world) {
        return sample(world).elapsed();
    }

    long day(World world) {
        return elapsedTicks(world) / TICKS_PER_DAY;
    }

    Season season(World world) {
        return date(day(world)).season();
    }

    Date current(World world) {
        return date(day(world));
    }

    static Date date(long elapsedDays) {
        if (elapsedDays < 0) throw new IllegalArgumentException("Elapsed calendar days must be nonnegative");
        long year = elapsedDays / DAYS_PER_YEAR + 1;
        int yearDay = (int) (elapsedDays % DAYS_PER_YEAR);
        int month = yearDay / DAYS_PER_MONTH + 1;
        int day = yearDay % DAYS_PER_MONTH + 1;
        Season season = switch (month) {
            case 3, 4, 5 -> Season.SPRING;
            case 6, 7, 8 -> Season.SUMMER;
            case 9, 10, 11 -> Season.AUTUMN;
            default -> Season.WINTER;
        };
        return new Date(year, month, day, season);
    }

    private Clock sample(World world) {
        UUID id = world.getUID();
        long current = Math.max(0, world.getFullTime());
        Clock prior = clocks.get(id);
        if (prior == null) {
            String path = ROOT + id;
            prior = new Clock(state.data().getLong(path + ".raw", current), state.data().getLong(path + ".elapsed"));
        }
        long advance = Math.max(0, current - prior.raw());
        Clock next = new Clock(current, Math.min(prior.elapsed(), Long.MAX_VALUE - advance) + advance);
        clocks.put(id, next);
        return next;
    }

    void tick() {
        for (World world : Bukkit.getWorlds()) sample(world);
        save(false);
    }

    void stop() {
        for (World world : Bukkit.getWorlds()) sample(world);
        save(true);
    }

    private void save(boolean allChanges) {
        Map<UUID, Clock> pending = new LinkedHashMap<>();
        clocks.forEach((id, clock) -> {
            String path = ROOT + id;
            long previous = state.data().getLong(path + ".elapsed", -1);
            long raw = state.data().getLong(path + ".raw", -1);
            boolean changed = previous < 0 || previous / TICKS_PER_DAY != clock.elapsed() / TICKS_PER_DAY
                || clock.raw() < raw || (allChanges && (raw != clock.raw() || previous != clock.elapsed()));
            if (changed) pending.put(id, clock);
        });
        if (pending.isEmpty()) return;
        state.transaction(undo -> pending.forEach((id, clock) -> {
            String path = ROOT + id;
            state.data().set(path + ".raw", clock.raw());
            state.data().set(path + ".elapsed", clock.elapsed());
        }));
    }

    @EventHandler
    public void load(WorldLoadEvent event) {
        sample(event.getWorld());
        save(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(WorldUnloadEvent event) {
        sample(event.getWorld());
        save(true);
        clocks.remove(event.getWorld().getUID());
    }
}
