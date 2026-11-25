package dev.townyeconomylaw;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Government {
    enum Role { MINISTER, LEGISLATOR, JUDGE, WARDEN, TREASURER, PROSECUTOR, POLICEMAN, POLITICIAN }
    enum Plot { JAIL, COURTHOUSE, POLICE, LEGISLATURE }

    private final State state;
    private final Integrations api;

    Government(State state, Integrations api) {
        this.state = state;
        this.api = api;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    Integrations.Nation citizenship(Player player) {
        Integrations.Nation nation = api.nation(player.getUniqueId());
        require(nation != null, "Towny の国に所属している必要があります。");
        return nation;
    }

    void grant(Player actor, UUID target, Role role, boolean grant) {
        Integrations.Nation nation = citizenship(actor);
        boolean leader = actor.getUniqueId().equals(nation.leader());
        require(leader || (role != Role.MINISTER && hasRole(nation, actor.getUniqueId(), Role.MINISTER)),
            "この役職を変更する権限がありません。");
        Integrations.Nation recipient = api.nation(target);
        require(!grant || (recipient != null && nation.id().equals(recipient.id())), "同じ国の住民にのみ任命できます。");
        require(leader || !hasRole(nation, target, Role.MINISTER), "大臣の役職は国のリーダーのみ変更できます。");
        String path = rolePath(nation, target, role);
        state.transaction(undo -> data().set(path, grant ? true : null));
    }

    Set<String> roles(Player player) {
        Integrations.Nation nation = citizenship(player);
        return state.keys(prefix(nation) + ".roles." + player.getUniqueId());
    }

    void tax(Player player, long rate) {
        Integrations.Nation nation = authorize(player, Role.TREASURER);
        state.transaction(undo -> data().set(prefix(nation) + ".tax", rate));
    }

    String tax(Player player) {
        String path = prefix(citizenship(player));
        return "証券税 " + money(data().getLong(path + ".tax")) + "% / 国庫残高 "
            + money(data().getLong(path + ".treasury"));
    }

    void collect(Player player) {
        Integrations.Nation nation = citizenship(player);
        require(player.getUniqueId().equals(nation.leader()), "国庫の出金は国のリーダーのみ実行できます。");
        String path = prefix(nation) + ".treasury";
        long amount = data().getLong(path);
        require(amount > 0, "国庫残高がありません。");
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, true);
            data().set(path, null);
        });
        player.sendMessage("国庫から Vault へ出金しました。 " + money(amount));
    }

    void setPlot(Player actor, Plot plot) {
        Integrations.Nation nation = authorize(actor, switch (plot) {
            case JAIL -> Role.WARDEN;
            case COURTHOUSE -> Role.JUDGE;
            case POLICE -> Role.POLICEMAN;
            case LEGISLATURE -> Role.POLITICIAN;
        });
        Location location = actor.getLocation();
        Integrations.Nation territory = api.plotNation(location);
        require(territory != null && territory.id().equals(nation.id()), "自国の Towny プロット内で実行してください。");
        String plotKey = api.plotKey(location);
        require(plotKey != null, "Towny プロットが見つかりません。");
        state.transaction(undo -> {
            for (Plot type : Plot.values()) {
                String previous = plotPath(nation, type);
                if (type != plot && plotKey.equals(data().getString(previous + ".plot"))) data().set(previous, null);
            }
            String oldCourt = prefix(nation) + ".plots.courtroom";
            if (plot == Plot.COURTHOUSE || plotKey.equals(data().getString(oldCourt + ".plot"))) data().set(oldCourt, null);
            String entry = prefix(nation) + ".plot-types." + plotKey;
            data().set(entry, plot.name().toLowerCase(Locale.ROOT));
            saveLocation(plotPath(nation, plot), location, plotKey);
        });
    }

    String plotType(Player player) {
        Integrations.Nation nation = api.plotNation(player.getLocation());
        String key = api.plotKey(player.getLocation());
        if (nation == null || key == null) return "未設定";
        String type = data().getString(prefix(nation) + ".plot-types." + key);
        if (type != null) return type;
        if (key.equals(data().getString(plotPath(nation, Plot.JAIL) + ".plot"))) return "jail";
        return key.equals(data().getString(prefix(nation) + ".plots.courtroom.plot")) ? "courthouse" : "未設定";
    }

    Location court(Player player) {
        return activePlot(citizenship(player), Plot.COURTHOUSE);
    }

    private Location activePlot(Integrations.Nation nation, Plot plot) {
        String path = plotPath(nation, plot);
        if (plot == Plot.COURTHOUSE && !data().contains(path)) path = prefix(nation) + ".plots.courtroom";
        Location location = location(path);
        require(location != null, "司法プロットが未設定か、ワールドが読み込まれていません。");
        Integrations.Nation territory = api.plotNation(location);
        require(territory != null && territory.id().equals(nation.id())
            && data().getString(path + ".plot", "").equals(api.plotKey(location)), "司法プロットを再設定してください。");
        return location;
    }

    long jail(Player actor, Player target, int minutes, String lawId, Wanted wanted) {
        Integrations.Nation nation = authorize(actor, Role.WARDEN);
        Integrations.Nation subjectNation = api.nation(target.getUniqueId());
        require(!actor.equals(target), "自分を拘禁することはできません。");
        require(subjectNation != null && nation.id().equals(subjectNation.id()), "自国の住民のみ拘禁できます。");
        require(!jailed(target), "すでに拘禁されています。");
        require(data().contains(prefix(nation) + ".laws." + lawId)
            || data().contains(prefix(nation) + ".statutes." + lawId), "適用する法律が見つかりません。");
        Location anchor = activePlot(nation, Plot.JAIL);
        String plotKey = api.plotKey(anchor);
        String path = jailPath(target);
        long[] reward = {0};
        state.transaction(undo -> {
            saveLocation(path, anchor, plotKey);
            data().set(path + ".until", System.currentTimeMillis() + minutes * 60_000L);
            data().set(path + ".nation", nation.id().toString());
            data().set(path + ".law", lawId);
            data().set(path + ".officer", actor.getUniqueId().toString());
            reward[0] = wanted.capture(nation, actor, target, undo);
        });
        target.sendMessage("拘禁されました。 " + minutes + " 分 / 適用法 " + lawId);
        return reward[0];
    }

    void release(CommandSender actor, OfflinePlayer target) {
        String path = "jail." + target.getUniqueId();
        require(data().contains(path), "拘禁記録がありません。");
        if (!actor.hasPermission("townyeconomylaw.jail.override")) {
            require(actor instanceof Player, "この操作に必要な権限がありません。");
            Integrations.Nation nation = authorize((Player) actor, Role.WARDEN);
            require(nation.id().toString().equals(data().getString(path + ".nation")), "自国が拘禁した住民のみ釈放できます。");
        }
        state.transaction(undo -> data().set(path, null));
        Player online = target.getPlayer();
        if (online != null) online.sendMessage("釈放されました。");
    }

    boolean jailed(OfflinePlayer player) {
        return data().getLong(jailPath(player) + ".until") > System.currentTimeMillis();
    }

    Location anchor(Player player) {
        return location(jailPath(player));
    }

    boolean inside(Player player, Location destination) {
        if (destination.getWorld() == null) return false;
        String jail = jailPath(player);
        if (data().getString(jail + ".plot", "").equals(api.plotKey(destination))) return true;
        Location anchor = anchor(player);
        return anchor != null && destination.getWorld().equals(anchor.getWorld())
            && destination.getBlockX() == anchor.getBlockX()
            && destination.getBlockY() == anchor.getBlockY()
            && destination.getBlockZ() == anchor.getBlockZ();
    }

    private String jailPath(OfflinePlayer player) {
        return "jail." + player.getUniqueId();
    }

    private void saveLocation(String path, Location location, String plotKey) {
        data().set(path + ".world", location.getWorld().getUID().toString());
        data().set(path + ".x", location.getX());
        data().set(path + ".y", location.getY());
        data().set(path + ".z", location.getZ());
        data().set(path + ".yaw", location.getYaw());
        data().set(path + ".pitch", location.getPitch());
        data().set(path + ".plot", plotKey);
    }

    private Location location(String path) {
        String id = data().getString(path + ".world");
        if (id == null) return null;
        World world = Bukkit.getWorld(UUID.fromString(id));
        if (world == null) return null;
        return new Location(world, data().getDouble(path + ".x"), data().getDouble(path + ".y"),
            data().getDouble(path + ".z"), (float) data().getDouble(path + ".yaw"),
            (float) data().getDouble(path + ".pitch"));
    }

    private Integrations.Nation authorize(Player player, Role role) {
        Integrations.Nation nation = citizenship(player);
        UUID id = player.getUniqueId();
        require(id.equals(nation.leader()) || hasRole(nation, id, Role.MINISTER) || hasRole(nation, id, role)
            || (role == Role.WARDEN && hasRole(nation, id, Role.POLICEMAN)),
            "この操作に必要な国内の役職がありません。");
        return nation;
    }

    boolean hasRole(Integrations.Nation nation, UUID id, Role role) {
        return data().getBoolean(rolePath(nation, id, role));
    }

    private String rolePath(Integrations.Nation nation, UUID id, Role role) {
        return prefix(nation) + ".roles." + id + "." + role.name().toLowerCase(Locale.ROOT);
    }

    private String plotPath(Integrations.Nation nation, Plot plot) {
        return prefix(nation) + ".plots." + plot.name().toLowerCase(Locale.ROOT);
    }

    private String prefix(Integrations.Nation nation) {
        return "nations." + nation.id();
    }
}
