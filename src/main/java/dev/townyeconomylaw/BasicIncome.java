package dev.townyeconomylaw;

import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class BasicIncome implements Listener {
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final int DAYS = 7;

    private final State state;
    private final Integrations api;
    private final Government government;

    BasicIncome(State state, Integrations api, Government government) {
        this.state = state;
        this.api = api;
        this.government = government;
    }

    void set(Player actor, long amount) {
        Integrations.Nation nation = government.citizenship(actor);
        require(actor.getUniqueId().equals(nation.leader()), "国のリーダーのみベーシックインカムを設定できます。");
        require(amount >= 0 && amount <= Input.MAX_CASH, "支給額が範囲外です。");
        state.transaction(undo -> state.data().set("nations." + nation.id() + ".basic-income", amount));
        actor.sendMessage(amount == 0 ? "ベーシックインカムを無効にしました。"
            : "新規参加者へのベーシックインカムを " + money(amount) + " / 日に設定しました。");
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String entry = entry(player.getUniqueId());
        if (!player.hasPlayedBefore() && !state.data().contains(entry + ".joined")) {
            state.transaction(undo -> state.data().set(entry + ".joined", System.currentTimeMillis()));
        }
    }

    void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                pay(player, now);
            } catch (State.Rejected ignored) {
                // A rejected economy payment consumes no eligibility. The next tick can retry.
            } catch (RuntimeException failure) {
                Bukkit.getLogger().log(Level.WARNING, "Could not pay basic income for " + player.getUniqueId(), failure);
            }
        }
    }

    private void pay(Player player, long now) {
        UUID resident = player.getUniqueId();
        String entry = entry(resident);
        long joined = state.data().getLong(entry + ".joined", -1);
        if (joined < 0 || now < joined) return;
        long day = (now - joined) / DAY;
        if (day >= DAYS || state.data().getLong(entry + ".last-day", -1) >= day) return;

        Integrations.Nation nation = api.nation(resident);
        if (nation == null) return;
        String country = "nations." + nation.id();
        String treasury = country + ".treasury";
        long amount = state.data().getLong(country + ".basic-income");
        long available = state.data().getLong(treasury);
        if (amount <= 0 || available < amount) return;

        state.transaction(undo -> {
            api.economy(undo, resident, amount, true);
            state.data().set(treasury, available - amount);
            state.data().set(entry + ".last-day", day);
        });
        player.sendMessage(nation.name() + " の国庫からベーシックインカム " + money(amount) + " を受け取りました。");
    }

    private String entry(UUID player) {
        return "basic-income-players." + player;
    }
}
