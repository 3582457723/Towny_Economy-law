package dev.townyeconomylaw;

import java.util.HashSet;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.State.require;

final class Territory {
    private final State state;
    private final Integrations api;
    private final Government government;

    Territory(State state, Integrations api, Government government) {
        this.state = state;
        this.api = api;
        this.government = government;
    }

    void createPuppet(Player actor, String name, OfflinePlayer leader) {
        Integrations.Nation parent = parentLeader(actor);
        require(api.nation(name) == null, "同名の国が存在します。");
        Integrations.Town capital = api.town(leader.getUniqueId());
        require(capital != null && leader.getUniqueId().equals(capital.mayor()) && capital.nation() == null,
            "対象者は無所属の Towny 町の町長である必要があります。");
        Integrations.Nation child = api.createNation(actor, name, capital);
        state.transaction(undo -> link(parent.id(), child.id()));
        actor.sendMessage("傀儡国を設立しました。 " + child.name());
    }

    void invitePuppet(Player actor, String name) {
        Integrations.Nation parent = parentLeader(actor);
        Integrations.Nation child = nation(name);
        verifyPuppetLink(parent.id(), child.id());
        state.transaction(undo -> state.data().set(puppet(child.id()) + ".invites." + parent.id(), true));
        actor.sendMessage(child.name() + " に招待を送りました。");
    }

    void joinPuppet(Player actor, String name) {
        Integrations.Nation child = parentLeader(actor);
        Integrations.Nation parent = nation(name);
        verifyPuppetLink(parent.id(), child.id());
        require(state.data().getBoolean(puppet(child.id()) + ".invites." + parent.id()), "指定の親国からの招待がありません。");
        state.transaction(undo -> link(parent.id(), child.id()));
        actor.sendMessage(parent.name() + " の傀儡国になりました。");
    }

    private void link(UUID parent, UUID child) {
        state.data().set(puppet(child), null);
        state.data().set(puppet(child) + ".parent", parent.toString());
        state.data().set(puppet(child) + ".registration", UUID.randomUUID().toString());
    }

    private void verifyPuppetLink(UUID parent, UUID child) {
        require(!state.data().contains(puppet(child) + ".parent"), "対象国はすでに他国に従属しています。");
        HashSet<UUID> visited = new HashSet<>();
        for (UUID current = parent; current != null; ) {
            require(!current.equals(child) && visited.add(current), "従属関係を循環させることはできません。");
            String next = state.data().getString(puppet(current) + ".parent");
            current = next == null ? null : UUID.fromString(next);
        }
    }

    void annex(Player actor, String name) {
        Integrations.Nation parent = parentLeader(actor);
        Integrations.Nation child = nation(name);
        require(isPuppet(parent.id(), child.id()), "指定の国は自国の直接の傀儡国ではありません。");
        api.annexPlot(actor.getLocation(), child, parent);
        actor.sendMessage("現在地の1プロットを自国の首都へ移管しました。");
    }

    void createLocal(Player actor, String name, OfflinePlayer leader) {
        Integrations.Nation parent = parentLeader(actor);
        Player founder = leader.getPlayer();
        require(founder != null && api.town(leader.getUniqueId()) == null, "対象者は町に未所属でオンラインの必要があります。");
        require(api.town(name) == null, "同名の町が存在します。");
        Integrations.Town town = api.createTown(founder, name, parent);
        recordLocal(parent, town);
        actor.sendMessage("自治体を設立しました。 " + town.name());
    }

    void addLocal(Player actor, String name) {
        Integrations.Nation parent = parentLeader(actor);
        Integrations.Town town = town(name);
        require(town.nation() != null && parent.id().equals(town.nation().id()), "自国の町だけを自治体に指定できます。");
        require(!state.data().contains(local(town.id()) + ".parent"), "この町は登録済みです。");
        recordLocal(parent, town);
        actor.sendMessage("自治体を登録しました。 " + town.name());
    }

    private void recordLocal(Integrations.Nation parent, Integrations.Town town) {
        state.transaction(undo -> {
            state.data().set(local(town.id()) + ".parent", parent.id().toString());
            state.data().set(local(town.id()) + ".registration", UUID.randomUUID().toString());
        });
    }

    void departure(Player actor, String kind, String name, Civics civics) {
        Integrations.Nation parent = government.citizenship(actor);
        UUID subject = kind.equals("puppet") ? nation(name).id() : town(name).id();
        require(registered(parent.id(), kind, subject), "自国の傀儡国または自治体を指定してください。");
        if (actor.getUniqueId().equals(parent.leader())) {
            state.transaction(undo -> applyDeparture(parent.id(), kind, subject));
            actor.sendMessage("離脱を承認しました。 " + name);
        } else {
            require(civics.politician(parent, actor.getUniqueId()), "国のリーダーまたは政治家のみ承認手続きを行えます。");
            civics.territorialVote(actor, parent, kind, subject, name);
        }
    }

    void rebel(Player actor, String kind) {
        UUID id;
        String path;
        if (kind.equals("puppet")) {
            id = parentLeader(actor).id();
            path = puppet(id);
        } else {
            Integrations.Town town = api.town(actor.getUniqueId());
            require(town != null && actor.getUniqueId().equals(town.mayor()), "町長のみ申請できます。");
            path = local(town.id());
        }
        require(state.data().contains(path + ".parent"), "親国が登録されていません。");
        state.transaction(undo -> state.data().set(path + ".rebellion", true));
        actor.sendMessage("独立要求を記録しました。親国のリーダーまたは議会の承認が必要です。");
    }

    void status(Player actor) {
        Integrations.Nation nation = government.citizenship(actor);
        for (String id : state.keys("puppets")) {
            UUID country = UUID.fromString(id);
            if (isPuppet(nation.id(), country) || country.equals(nation.id())) {
                Integrations.Nation child = api.nationById(country);
                String parent = state.data().getString(puppet(country) + ".parent");
                if (child != null && parent != null) {
                    Integrations.Nation owner = api.nationById(UUID.fromString(parent));
                    actor.sendMessage(child.name() + " / 親国 " + (owner == null ? parent : owner.name())
                        + (state.data().getBoolean(puppet(country) + ".rebellion") ? " / 独立要求あり" : ""));
                }
            }
        }
        for (String id : state.keys("local-governments")) {
            UUID townId = UUID.fromString(id);
            if (!registered(nation.id(), "local", townId)) continue;
            Integrations.Town town = api.townById(townId);
            if (town != null) actor.sendMessage("自治体 " + town.name()
                + (state.data().getBoolean(local(townId) + ".rebellion") ? " / 離脱要求あり" : ""));
        }
    }

    void applyDeparture(UUID parent, String kind, UUID subject) {
        require(registered(parent, kind, subject), "対象の所属関係が変更されています。");
        if (kind.equals("puppet")) {
            state.data().set(puppet(subject), null);
        } else {
            Integrations.Town town = api.townById(subject);
            require(town != null, "Towny の町が見つかりません。");
            if (town.nation() != null) {
                require(parent.equals(town.nation().id()), "Towny の所属国が変更されています。");
                api.detachTown(town);
            }
            state.data().set(local(subject), null);
        }
    }

    private boolean registered(UUID parent, String kind, UUID subject) {
        return parent.toString().equals(state.data().getString(
            (kind.equals("puppet") ? puppet(subject) : local(subject)) + ".parent"));
    }

    String registration(String kind, UUID subject) {
        return state.data().getString((kind.equals("puppet") ? puppet(subject) : local(subject)) + ".registration", "");
    }

    boolean registeredLocal(UUID parent, UUID town) {
        return registered(parent, "local", town);
    }

    private boolean isPuppet(UUID parent, UUID child) {
        return registered(parent, "puppet", child);
    }

    private String puppet(UUID nation) {
        return "puppets." + nation;
    }

    private String local(UUID town) {
        return "local-governments." + town;
    }

    private Integrations.Nation parentLeader(Player actor) {
        Integrations.Nation nation = government.citizenship(actor);
        require(actor.getUniqueId().equals(nation.leader()), "国のリーダーのみ実行できます。");
        return nation;
    }

    private Integrations.Nation nation(String name) {
        Integrations.Nation nation = api.nation(name);
        require(nation != null, "指定の国が見つかりません。");
        return nation;
    }

    private Integrations.Town town(String name) {
        Integrations.Town town = api.town(name);
        require(town != null, "指定の町が見つかりません。");
        return town;
    }
}
