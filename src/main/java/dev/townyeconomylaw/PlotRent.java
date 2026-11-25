package dev.townyeconomylaw;

import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class PlotRent {
    private static final String ROOT = "plot-rent";
    private static final long MONTH = Duration.ofDays(30).toMillis();
    private final State state;
    private final Integrations api;
    private Plugin plugin;
    private boolean accessReady;

    PlotRent(State state, Integrations api) {
        this.state = state;
        this.api = api;
    }

    void registerAccess(Plugin plugin) {
        this.plugin = plugin;
        accessReady = api.registerPlotTenancy(plugin, this::tenantAccess);
    }

    void add(Player landlord, OfflinePlayer tenant, long amount) {
        require(accessReady, "この Towny では区画賃貸を利用できません。");
        Integrations.PlotAccess plot = owned(landlord);
        UUID person = tenant.getUniqueId();
        require(!landlord.getUniqueId().equals(person), "自分には貸し出せません。");
        String path = contract(plot, person);
        require(!matches(path, plot), "この借主の申請または契約がすでにあります。");
        Location origin = landlord.getLocation();
        state.transaction(undo -> {
            data().set(path, null);
            data().set(path + ".owner", landlord.getUniqueId().toString());
            data().set(path + ".ownership", plot.ownership());
            data().set(path + ".world", origin.getWorld().getUID().toString());
            data().set(path + ".x", origin.getBlockX());
            data().set(path + ".y", origin.getBlockY());
            data().set(path + ".z", origin.getBlockZ());
            data().set(path + ".offer", amount);
        });
        landlord.sendMessage("賃貸を提案しました。30日ごとの賃料 " + money(amount));
        Player receiver = tenant.getPlayer();
        if (receiver != null) receiver.sendMessage(landlord.getName() + " から区画の賃貸提案があります。"
            + origin.getWorld().getName() + " " + origin.getBlockX() + ", " + origin.getBlockZ()
            + " で /tel plot rent accept");
    }

    void set(Player landlord, OfflinePlayer tenant, long amount) {
        Integrations.PlotAccess plot = owned(landlord);
        String path = contract(plot, tenant.getUniqueId());
        require(matches(path, plot), "貸出の申請または契約がありません。");
        state.transaction(undo -> data().set(path + ".offer", amount));
        landlord.sendMessage("賃料の変更を提案しました。借主の同意後、次の支払いから適用します。");
    }

    void remove(Player landlord, OfflinePlayer tenant) {
        Integrations.PlotAccess plot = owned(landlord);
        end(landlord, plot, tenant.getUniqueId());
    }

    void leave(Player tenant) {
        end(tenant, plot(tenant), tenant.getUniqueId());
    }

    private void end(Player actor, Integrations.PlotAccess plot, UUID tenant) {
        String path = contract(plot, tenant);
        require(matches(path, plot), "貸出の申請または契約がありません。");
        boolean paid = data().getLong(path + ".until") > System.currentTimeMillis();
        state.transaction(undo -> {
            if (paid) {
                data().set(path + ".renew", false);
                data().set(path + ".offer", null);
            } else data().set(path, null);
        });
        invalidate(tenant);
        actor.sendMessage(paid ? "自動更新を停止しました。支払済み期間の利用は継続できます。" : "賃貸申請・契約を解除しました。");
    }

    void accept(Player tenant) {
        require(accessReady, "この Towny では区画賃貸を利用できません。");
        Integrations.PlotAccess plot = plot(tenant);
        UUID person = tenant.getUniqueId();
        String path = contract(plot, person);
        require(matches(path, plot) && data().contains(path + ".offer"), "現在地に受諾できる提案がありません。");
        long amount = data().getLong(path + ".offer");
        long now = System.currentTimeMillis();
        boolean paid = data().getLong(path + ".until") > now;
        state.transaction(undo -> {
            if (!paid) {
                pay(undo, plot.controller(), person, amount);
                data().set(path + ".until", now + MONTH);
            }
            data().set(path + ".rent", amount);
            data().set(path + ".renew", true);
            data().set(path + ".offer", null);
        });
        invalidate(person);
        tenant.sendMessage(paid ? "新しい賃料を承認しました。次の支払いから " + money(amount)
            : "賃貸契約を開始しました。30日分の賃料 " + money(amount));
    }

    void show(Player player) {
        Integrations.PlotAccess plot = plot(player);
        String path = contract(plot, player.getUniqueId());
        if (matches(path, plot)) {
            if (data().contains(path + ".offer")) player.sendMessage("提示賃料 " + money(data().getLong(path + ".offer")) + " /tel plot rent accept");
            if (data().contains(path + ".rent")) {
                long until = data().getLong(path + ".until");
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z", java.util.Locale.ROOT)
                    .withZone(java.time.ZoneId.of("Asia/Tokyo"));
                player.sendMessage("現在賃料 " + money(data().getLong(path + ".rent")) + " / 支払済み期限 "
                    + formatter.format(java.time.Instant.ofEpochMilli(until)) + " / 自動更新 " + (data().getBoolean(path + ".renew") ? "有" : "無"));
            }
        } else if (player.getUniqueId().equals(plot.controller())) {
            for (String id : state.keys(ROOT + "." + plot.key())) {
                String lease = ROOT + "." + plot.key() + "." + id;
                if (matches(lease, plot)) {
                    OfflinePlayer tenant = Bukkit.getOfflinePlayer(UUID.fromString(id));
                    player.sendMessage(java.util.Objects.toString(tenant.getName(), id) + " / "
                        + money(data().getLong(lease + ".rent", data().getLong(lease + ".offer"))));
                }
            }
        } else player.sendMessage("現在地に自分の賃貸契約はありません。");
    }

    void tick() {
        if (!accessReady) return;
        long now = System.currentTimeMillis();
        for (String key : state.keys(ROOT)) {
            for (String person : state.keys(ROOT + "." + key)) {
                String path = ROOT + "." + key + "." + person;
                long end = data().getLong(path + ".until");
                if (end == 0 || now < end) continue;
                UUID tenant = UUID.fromString(person);
                try {
                    update(path, tenant, now);
                } catch (State.Rejected paymentUnavailable) {
                    // An unpaid tenant has no additional access until the next payment succeeds.
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING, "Could not renew rental " + key + " for " + person, failure);
                }
            }
        }
    }

    private void update(String path, UUID tenant, long now) {
        invalidate(tenant);
        if (!data().getBoolean(path + ".renew")) {
            state.transaction(undo -> data().set(path, null));
            return;
        }
        World world = Bukkit.getWorld(UUID.fromString(data().getString(path + ".world")));
        if (world == null) return;
        Location location = new Location(world, data().getInt(path + ".x"), data().getInt(path + ".y"), data().getInt(path + ".z"));
        Integrations.PlotAccess plot = api.plotAccess(location);
        if (plot == null || !matches(path, plot)) {
            state.transaction(undo -> data().set(path, null));
            return;
        }
        long amount = data().getLong(path + ".rent");
        state.transaction(undo -> {
            pay(undo, plot.controller(), tenant, amount);
            data().set(path + ".until", now + MONTH);
        });
    }

    private void pay(java.util.ArrayDeque<Runnable> undo, UUID landlord, UUID tenant, long amount) {
        api.economy(undo, tenant, amount, false);
        api.economy(undo, landlord, amount, true);
    }

    private boolean tenantAccess(UUID tenant, Integrations.PlotAccess plot) {
        String path = contract(plot, tenant);
        return matches(path, plot) && data().getLong(path + ".until") > System.currentTimeMillis();
    }

    private boolean matches(String path, Integrations.PlotAccess plot) {
        return plot.controller() != null && plot.controller().toString().equals(data().getString(path + ".owner"))
            && plot.ownership().equals(data().getString(path + ".ownership"));
    }

    private void invalidate(UUID tenant) {
        if (accessReady) api.invalidatePlotTenant(tenant);
    }

    private Integrations.PlotAccess owned(Player player) {
        Integrations.PlotAccess plot = plot(player);
        require(player.getUniqueId().equals(plot.controller()), "このプロットの管理者のみ実行できます。");
        return plot;
    }

    private Integrations.PlotAccess plot(Player player) {
        Integrations.PlotAccess plot = api.plotAccess(player.getLocation());
        require(plot != null, "Towny の登録済みプロットで実行してください。");
        return plot;
    }

    private String contract(Integrations.PlotAccess plot, UUID tenant) {
        return ROOT + "." + plot.key() + "." + tenant;
    }

    private YamlConfiguration data() {
        return state.data();
    }
}
