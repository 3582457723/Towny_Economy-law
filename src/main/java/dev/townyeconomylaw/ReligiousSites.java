package dev.townyeconomylaw;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.State.require;

final class ReligiousSites {
    private static final String ROOT = "religious-sites";

    private final State state;
    private final Integrations api;
    private final Government government;

    ReligiousSites(State state, Integrations api, Government government) {
        this.state = state;
        this.api = api;
        this.government = government;
    }

    void mark(Player player) {
        Integrations.PlotAccess plot = owned(player);
        if (!registered(plot)) set(player, "宗教施設", "");
        else player.sendMessage("現在地は登録済みの宗教施設です。 " + description(entry(plot.key())));
    }

    void set(Player player, String name, String faith) {
        Location location = player.getLocation();
        Integrations.PlotAccess plot = owned(player);
        Integrations.Nation nation = api.plotNation(location);
        String previous = government.plotType(player);
        if (nation != null && !previous.equals("未設定")) {
            require(player.getUniqueId().equals(nation.leader())
                || government.hasRole(nation, player.getUniqueId(), Government.Role.MINISTER),
                "国の施設を変更できるのはリーダーまたは大臣です。");
        }

        String locationKey = entry(plot.key());
        state.transaction(undo -> {
            if (nation != null) government.clearPlotDesignation(nation, plot.key());
            state.data().set(locationKey + ".ownership", plot.ownership());
            state.data().set(locationKey + ".name", name);
            state.data().set(locationKey + ".faith", faith);
            state.data().set(locationKey + ".world", location.getWorld().getUID().toString());
            state.data().set(locationKey + ".x", location.getBlockX());
            state.data().set(locationKey + ".y", location.getBlockY());
            state.data().set(locationKey + ".z", location.getBlockZ());
        });
        player.sendMessage("現在地のプロットを宗教施設に登録しました。 " + name);
    }

    boolean registered(Player player) {
        return registered(api.plotAccess(player.getLocation()));
    }

    private boolean registered(Integrations.PlotAccess plot) {
        return plot != null && plot.ownership().equals(state.data().getString(entry(plot.key()) + ".ownership"));
    }

    void command(Player player, List<String> args) {
        String operation = args.size() < 3 ? "show" : args.get(2).toLowerCase(Locale.ROOT);
        switch (operation) {
            case "set" -> {
                require(args.size() == 4 || args.size() == 5, "使い方 /tel plot religious set <施設名> [宗教名]");
                set(player, label(args.get(3)), args.size() == 5 ? label(args.get(4)) : "");
            }
            case "show" -> {
                Integrations.PlotAccess plot = api.plotAccess(player.getLocation());
                require(registered(plot), "現在地に宗教施設は登録されていません。");
                String path = entry(plot.key());
                player.sendMessage(description(path));
            }
            case "list" -> list(player);
            case "remove" -> {
                Integrations.PlotAccess plot = owned(player);
                require(registered(plot), "現在地に宗教施設は登録されていません。");
                state.transaction(undo -> state.data().set(entry(plot.key()), null));
                player.sendMessage("宗教施設の登録を解除しました。");
            }
            default -> throw new State.Rejected("使い方 /tel plot religious <set|show|list|remove>");
        }
    }

    private void list(Player player) {
        var world = player.getWorld();
        String id = world.getUID().toString();
        boolean any = false;
        for (String key : state.keys(ROOT)) {
            String path = entry(key);
            if (!id.equals(state.data().getString(path + ".world"))) continue;
            int x = state.data().getInt(path + ".x");
            int y = state.data().getInt(path + ".y");
            int z = state.data().getInt(path + ".z");
            Integrations.PlotAccess plot = api.plotAccess(new Location(world, x, y, z));
            if (plot == null || !key.equals(plot.key()) || !registered(plot)) continue;
            player.sendMessage(description(path) + " / " + x + ", " + y + ", " + z);
            any = true;
        }
        if (!any) player.sendMessage("このワールドに公開された宗教施設はありません。");
    }

    private String description(String path) {
        String faith = state.data().getString(path + ".faith", "");
        return state.data().getString(path + ".name", "宗教施設") + (faith.isBlank() ? "" : " / " + faith);
    }

    private Integrations.PlotAccess owned(Player player) {
        Integrations.PlotAccess plot = api.plotAccess(player.getLocation());
        require(plot != null && Objects.equals(player.getUniqueId(), plot.controller()), "Towny プロットの管理者のみ実行できます。");
        return plot;
    }

    private String label(String value) {
        String text = value.strip();
        require(!text.isEmpty() && text.length() <= 60, "施設名と宗教名は1〜60文字です。");
        return text;
    }

    static String entry(String key) {
        return ROOT + "." + key;
    }
}
