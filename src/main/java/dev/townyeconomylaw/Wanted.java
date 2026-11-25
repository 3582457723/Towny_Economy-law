package dev.townyeconomylaw;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Input.cents;
import static dev.townyeconomylaw.Input.knownPlayer;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Wanted {
    private static final String USAGE = "使い方 /tel nation wanted <create|revoke|list> [MCID] [懸賞金]";

    private final State state;
    private final Integrations api;
    private final Government government;

    Wanted(State state, Integrations api, Government government) {
        this.state = state;
        this.api = api;
        this.government = government;
    }

    void command(Player actor, List<String> args) {
        String action = args.size() <= 2 ? "list" : args.get(2).toLowerCase(Locale.ROOT);
        Integrations.Nation nation = government.citizenship(actor);
        if (action.equals("list")) {
            require(args.size() <= 3, USAGE);
            list(actor, nation);
            return;
        }

        UUID officer = actor.getUniqueId();
        require(officer.equals(nation.leader()) || government.hasRole(nation, officer, Government.Role.MINISTER)
            || government.hasRole(nation, officer, Government.Role.PROSECUTOR), "リーダー・大臣・検察官のみ指名手配を変更できます。");
        switch (action) {
            case "create" -> {
                require(args.size() == 5, USAGE);
                create(actor, nation, knownPlayer(args.get(3)), cents(args.get(4), 1, Input.MAX_CASH));
            }
            case "revoke" -> {
                require(args.size() == 4 || args.size() == 5, USAGE);
                Long expected = args.size() == 5 ? cents(args.get(4), 1, Input.MAX_CASH) : null;
                revoke(actor, nation, knownPlayer(args.get(3)), expected);
            }
            default -> throw new State.Rejected(USAGE);
        }
    }

    private void create(Player actor, Integrations.Nation nation, OfflinePlayer suspect, long reward) {
        UUID target = suspect.getUniqueId();
        require(!actor.getUniqueId().equals(target), "自分を指名手配することはできません。");
        Integrations.Nation membership = api.nation(target);
        require(membership != null && nation.id().equals(membership.id()), "自国の住民のみ指名手配できます。");
        require(!government.jailed(suspect), "この住民はすでに拘禁されています。");
        String path = path(nation, target);
        String treasury = treasury(nation);
        require(!state.data().contains(path), "この住民はすでに指名手配されています。");
        long funds = state.data().getLong(treasury);
        require(funds >= reward, "国庫残高が不足しています。");
        String name = suspect.getName() == null ? target.toString() : suspect.getName();

        state.transaction(undo -> {
            state.data().set(treasury, funds - reward);
            state.data().set(path + ".name", name);
            state.data().set(path + ".issuer", actor.getUniqueId().toString());
            state.data().set(path + ".reward", reward);
        });
        actor.sendMessage(name + " を指名手配しました。国庫から懸賞金 " + money(reward) + " を確保しました。");
    }

    private void revoke(Player actor, Integrations.Nation nation, OfflinePlayer suspect, Long expected) {
        String path = path(nation, suspect.getUniqueId());
        require(state.data().contains(path), "この住民の指名手配が見つかりません。");
        long reward = state.data().getLong(path + ".reward");
        require(expected == null || expected == reward, "指定額が現在の懸賞金と一致しません。");
        state.transaction(undo -> refund(nation, path, reward));
        actor.sendMessage("指名手配を取り消し、国庫へ " + money(reward) + " を戻しました。");
    }

    private void list(Player actor, Integrations.Nation nation) {
        List<String> targets = state.keys(root(nation)).stream().sorted().toList();
        if (targets.isEmpty()) {
            actor.sendMessage("有効な指名手配はありません。");
            return;
        }
        for (String target : targets) {
            String path = root(nation) + "." + target;
            actor.sendMessage(state.data().getString(path + ".name", target) + " / 懸賞金 " + money(state.data().getLong(path + ".reward")));
        }
    }

    // Called only once the arrest's legal checks have passed, within that arrest's transaction.
    long capture(Integrations.Nation nation, Player officer, Player suspect, ArrayDeque<Runnable> undo) {
        String path = path(nation, suspect.getUniqueId());
        if (!state.data().contains(path)) return 0;
        UUID id = officer.getUniqueId();
        long reward = state.data().getLong(path + ".reward");
        boolean eligible = !id.equals(suspect.getUniqueId()) && !id.toString().equals(state.data().getString(path + ".issuer"))
            && (government.hasRole(nation, id, Government.Role.POLICEMAN) || government.hasRole(nation, id, Government.Role.WARDEN));
        if (!eligible) {
            refund(nation, path, reward);
            return 0;
        }
        api.economy(undo, id, reward, true);
        state.data().set(path, null);
        return reward;
    }

    private void refund(Integrations.Nation nation, String path, long reward) {
        String treasury = treasury(nation);
        state.data().set(treasury, Math.addExact(state.data().getLong(treasury), reward));
        state.data().set(path, null);
    }

    private String treasury(Integrations.Nation nation) {
        return Civics.nationPath(nation.id()) + ".treasury";
    }

    private String root(Integrations.Nation nation) {
        return Civics.nationPath(nation.id()) + ".wanted";
    }

    private String path(Integrations.Nation nation, UUID suspect) {
        return root(nation) + "." + suspect;
    }
}
