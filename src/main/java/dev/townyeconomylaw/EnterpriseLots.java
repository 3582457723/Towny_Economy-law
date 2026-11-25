package dev.townyeconomylaw;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Enterprises.path;
import static dev.townyeconomylaw.State.require;

final class EnterpriseLots {
    record Lot(String id, String name, String type, String station, Set<String> shares) {}
    record Claim(Enterprises.Firm firm, String key, Lot lot) {}

    private final State state;
    private final Integrations api;
    private final Enterprises firms;

    EnterpriseLots(State state, Integrations api, Enterprises firms) {
        this.state = state;
        this.api = api;
        this.firms = firms;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    Claim at(Location location) {
        Integrations.PlotAccess plot = api.plotAccess(location);
        return plot == null ? null : at(plot);
    }

    private Claim at(Integrations.PlotAccess plot) {
        String index = "enterprise-plots." + plot.key();
        String id = data().getString(index);
        Enterprises.Firm firm = id == null ? null : firms.get(id);
        if (firm == null) return null;
        String entry = plotPath(firm, plot.key());
        if (!plot.ownership().equals(data().getString(entry + ".ownership"))) return null;
        return new Claim(firm, plot.key(), lot(firm, data().getString(entry + ".lot")));
    }

    Claim own(Location location, Enterprises.Firm firm) {
        Claim claim = at(location);
        require(claim != null && claim.firm().id().equals(firm.id()), "現在地は選択した組織のプロットではありません。");
        return claim;
    }

    void claim(Player player, Enterprises.Firm firm) {
        Integrations.PlotAccess plot = plot(player.getLocation());
        require(player.getUniqueId().equals(plot.controller()), "Towny の区画所有者、または公有地の場合は町長のみ申請できます。");
        require(at(plot) == null, "このプロットはすでに組織に登録されています。");
        if (firm.leader().equals(player.getUniqueId())) {
            state.transaction(undo -> add(firm, plot));
            player.sendMessage("プロットを取得しました。");
        } else {
            String request = path(firm) + ".requests." + plot.key();
            state.transaction(undo -> data().set(request, player.getUniqueId().toString()));
            player.sendMessage("取得を申請しました。代表は現地で add_lot または reject_lot を実行できます。");
        }
    }

    void decide(Player player, Enterprises.Firm firm, boolean accept) {
        firms.leader(player, firm);
        Integrations.PlotAccess plot = plot(player.getLocation());
        String request = path(firm) + ".requests." + plot.key();
        String applicant = data().getString(request);
        require(applicant != null, "この場所に取得申請がありません。");
        if (accept) {
            UUID member = UUID.fromString(applicant);
            require(member.equals(plot.controller()) && firms.member(firm, member), "申請者の所属または Towny の所有権が変更されています。");
            require(at(plot) == null, "このプロットはすでに組織に登録されています。");
        }
        state.transaction(undo -> {
            if (accept) add(firm, plot);
            data().set(request, null);
        });
        player.sendMessage(accept ? "取得を承認しました。" : "取得申請を却下しました。");
    }

    private void add(Enterprises.Firm firm, Integrations.PlotAccess plot) {
        String old = data().getString("enterprise-plots." + plot.key());
        if (old != null) {
            Enterprises.Firm previous = firms.get(old);
            if (previous != null) detach(previous, plot.key());
        }
        Set<String> adjacent = new LinkedHashSet<>();
        for (String neighbor : neighbors(plot.key())) {
            String id = data().getString(plotPath(firm, neighbor) + ".lot");
            if (id != null) adjacent.add(id);
        }
        Lot combined = new Lot(UUID.randomUUID().toString(), "", "", "", Set.of());
        if (!adjacent.isEmpty()) {
            var iterator = adjacent.iterator();
            combined = lot(firm, iterator.next());
            while (iterator.hasNext()) combined = merge(combined, lot(firm, iterator.next()));
        }
        saveLot(firm, combined);
        for (String id : adjacent) {
            if (id.equals(combined.id())) continue;
            for (String key : plots(firm, id)) data().set(plotPath(firm, key) + ".lot", combined.id());
            data().set(lotPath(firm, id), null);
        }
        String entry = plotPath(firm, plot.key());
        data().set(entry + ".lot", combined.id());
        data().set(entry + ".ownership", plot.ownership());
        data().set("enterprise-plots." + plot.key(), firm.id());
    }

    private Lot merge(Lot left, Lot right) {
        Set<String> shares = new HashSet<>(left.shares());
        shares.addAll(right.shares());
        return new Lot(left.id(), compatible(left.name(), right.name()), compatible(left.type(), right.type()),
            compatible(left.station(), right.station()), shares);
    }

    private String compatible(String left, String right) {
        require(left.isEmpty() || right.isEmpty() || left.equalsIgnoreCase(right),
            "隣接ロットの名前・種別が競合しています。先に設定を揃えてください。");
        return left.isEmpty() ? right : left;
    }

    void unclaim(Player player, Enterprises.Firm firm) {
        Location location = player.getLocation();
        Integrations.PlotAccess plot = api.plotAccess(location);
        String key = plot == null ? api.plotCoordinate(location) : plot.key();
        require(data().contains(plotPath(firm, key)), "この組織のプロットではありません。");
        require(firm.leader().equals(player.getUniqueId()) || (plot != null && player.getUniqueId().equals(plot.controller())),
            "組織代表または Towny の区画管理者のみ解除できます。");
        state.transaction(undo -> detach(firm, key));
        player.sendMessage("プロット登録を解除しました。");
    }

    private void detach(Enterprises.Firm firm, String key) {
        String oldId = data().getString(plotPath(firm, key) + ".lot");
        if (oldId == null) return;
        Lot old = lot(firm, oldId);
        data().set(plotPath(firm, key), null);
        data().set("enterprise-plots." + key, null);
        for (String registry : List.of("transit-machines", "transit-gates", "station-rails")) {
            for (String device : state.keys(registry)) {
                String entry = registry + "." + device;
                if (key.equals(data().getString(entry + ".plot"))) data().set(entry, null);
            }
        }
        List<Set<String>> groups = connected(plots(firm, oldId));
        if (groups.isEmpty()) data().set(lotPath(firm, oldId), null);
        for (int index = 1; index < groups.size(); index++) {
            String id = UUID.randomUUID().toString();
            // Split components keep their existing access and purpose. The original component keeps the lot name.
            saveLot(firm, new Lot(id, "", old.type(), old.station(), old.shares()));
            for (String plot : groups.get(index)) data().set(plotPath(firm, plot) + ".lot", id);
        }
    }

    static List<Set<String>> connected(Set<String> plots) {
        Set<String> remaining = new LinkedHashSet<>(plots.stream().sorted().toList());
        List<Set<String>> groups = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String first = remaining.iterator().next();
            Set<String> group = new LinkedHashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(first);
            remaining.remove(first);
            while (!queue.isEmpty()) {
                String key = queue.remove();
                group.add(key);
                for (String next : neighbors(key)) {
                    if (remaining.remove(next)) queue.add(next);
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private static List<String> neighbors(String key) {
        String[] fields = key.split(":");
        String world = fields[0] + ":";
        int x = Integer.parseInt(fields[1]);
        int z = Integer.parseInt(fields[2]);
        return List.of(world + (x - 1) + ":" + z, world + (x + 1) + ":" + z,
            world + x + ":" + (z - 1), world + x + ":" + (z + 1));
    }

    void configure(Player player, Enterprises.Firm firm, String setting, String name) {
        firms.leader(player, firm);
        Lot current = own(player.getLocation(), firm).lot();
        Lot next;
        if (setting.equals("name")) {
            require(state.keys(path(firm) + ".lots").stream().noneMatch(id -> !id.equals(current.id())
                && lot(firm, id).name().equalsIgnoreCase(name)), "同名のロットがあります。");
            next = new Lot(current.id(), name, current.type(), current.station(), current.shares());
        } else {
            require(List.of("station", "branch", "headquarter", "port").contains(setting), "ロット種別が無効です。");
            require(name.isEmpty() || setting.equals("station") || setting.equals("port"), "駅・港名は station または port で指定してください。");
            next = new Lot(current.id(), current.name(), setting, name, current.shares());
        }
        state.transaction(undo -> saveLot(firm, next));
        player.sendMessage("ロット情報を設定しました。");
    }

    void share(Player player, Enterprises.Firm firm, Enterprises.Firm recipient, String name) {
        firms.leader(player, firm);
        require(!firm.id().equals(recipient.id()), "別の企業コードを指定してください。");
        String id = state.keys(path(firm) + ".lots").stream().filter(key -> lot(firm, key).name().equalsIgnoreCase(name))
            .findFirst().orElseThrow(() -> new State.Rejected("指定のロット名が見つかりません。"));
        Lot current = lot(firm, id);
        Set<String> shares = new HashSet<>(current.shares());
        shares.add(recipient.id());
        state.transaction(undo -> saveLot(firm, new Lot(id, current.name(), current.type(), current.station(), shares)));
        player.sendMessage(recipient.name() + " にロットを共有しました。");
    }

    boolean accessible(Claim claim, Enterprises.Firm firm) {
        return claim.firm().id().equals(firm.id()) || claim.lot().shares().contains(firm.id());
    }

    private Set<String> plots(Enterprises.Firm firm, String id) {
        Set<String> matches = new LinkedHashSet<>();
        for (String key : state.keys(path(firm) + ".plots")) {
            if (id.equals(data().getString(plotPath(firm, key) + ".lot"))) matches.add(key);
        }
        return matches;
    }

    private Integrations.PlotAccess plot(Location location) {
        Integrations.PlotAccess plot = api.plotAccess(location);
        require(plot != null, "Towny の登録済みプロットで実行してください。");
        return plot;
    }

    private Lot lot(Enterprises.Firm firm, String id) {
        String root = lotPath(firm, id);
        return new Lot(id, data().getString(root + ".name", ""), data().getString(root + ".type", ""),
            data().getString(root + ".station", ""), Set.copyOf(data().getStringList(root + ".shares")));
    }

    private void saveLot(Enterprises.Firm firm, Lot lot) {
        String root = lotPath(firm, lot.id());
        data().set(root + ".name", lot.name());
        data().set(root + ".type", lot.type());
        data().set(root + ".station", lot.station());
        data().set(root + ".shares", lot.shares().stream().sorted().toList());
    }

    private String lotPath(Enterprises.Firm firm, String id) {
        return path(firm) + ".lots." + id;
    }

    private String plotPath(Enterprises.Firm firm, String key) {
        return path(firm) + ".plots." + key;
    }
}
