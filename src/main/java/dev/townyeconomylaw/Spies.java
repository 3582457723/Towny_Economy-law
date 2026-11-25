package dev.townyeconomylaw;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Civics.nationPath;
import static dev.townyeconomylaw.State.require;

final class Spies {
    private static final long PAY_PERIOD = Duration.ofDays(30).toMillis();

    private final State state;
    private final Integrations api;
    private final Government government;
    private final Logger logger;

    Spies(State state, Integrations api, Government government, Logger logger) {
        this.state = state;
        this.api = api;
        this.government = government;
        this.logger = logger;
    }

    void command(Player actor, List<String> args) {
        Integrations.Nation nation = government.citizenship(actor);
        require(official(nation, actor.getUniqueId()), "国のリーダーまたは大臣のみ実行できます。");
        require(args.size() >= 3, "使い方 /tel nation spy <hire|fire|list>");
        String operation = args.get(2).toLowerCase(Locale.ROOT);
        if (operation.equals("list")) {
            for (String member : state.keys("spy-contracts")) {
                String path = "spy-contracts." + member;
                if (nation.id().toString().equals(state.data().getString(path + ".nation"))) {
                    actor.sendMessage(state.data().getString(path + ".name") + " / 月給 " + Input.money(state.data().getLong(path + ".salary")));
                }
            }
            return;
        }
        require(args.size() >= 4 && (operation.equals("hire") || operation.equals("fire")),
            "使い方 /tel nation spy hire <MCID> <月給> または spy fire <MCID>");
        OfflinePlayer target = Input.knownPlayer(args.get(3));
        String path = "spy-contracts." + target.getUniqueId();
        String employer = state.data().getString(path + ".nation");
        if (operation.equals("fire")) {
            require(nation.id().toString().equals(employer), "自国の雇用記録が見つかりません。");
            state.transaction(undo -> state.data().set(path, null));
            actor.sendMessage("諜報員を解任しました。 " + target.getName());
            return;
        }
        require(args.size() == 5, "使い方 /tel nation spy hire <MCID> <月給>");
        require(employer == null || nation.id().toString().equals(employer) || api.nationById(UUID.fromString(employer)) == null,
            "対象者はすでに他国に雇われています。");
        long salary = Input.cents(args.get(4), 0, Input.MAX_CASH);
        long next = nation.id().toString().equals(employer) ? state.data().getLong(path + ".next")
            : System.currentTimeMillis() + PAY_PERIOD;
        state.transaction(undo -> {
            state.data().set(path + ".nation", nation.id().toString());
            state.data().set(path + ".name", target.getName());
            state.data().set(path + ".salary", salary);
            state.data().set(path + ".next", next);
        });
        actor.sendMessage("諜報員の契約を登録しました。 " + target.getName() + " / 月給 " + Input.money(salary));
    }

    UUID channel(UUID person, Integrations.Nation home) {
        if (home != null && official(home, person)) return home.id();
        return employer(person);
    }

    boolean receives(UUID person, Integrations.Nation home, String group) {
        if (home != null && official(home, person) && home.id().toString().equals(group)) return true;
        UUID employer = employer(person);
        return employer != null && employer.toString().equals(group);
    }

    private UUID employer(UUID person) {
        String employer = state.data().getString("spy-contracts." + person + ".nation");
        if (employer == null) return null;
        UUID id = UUID.fromString(employer);
        return api.nationById(id) == null ? null : id;
    }

    private boolean official(Integrations.Nation nation, UUID person) {
        return person.equals(nation.leader()) || government.hasRole(nation, person, Government.Role.MINISTER);
    }

    void tick() {
        long now = System.currentTimeMillis();
        for (String person : state.keys("spy-contracts")) {
            String path = "spy-contracts." + person;
            long salary = state.data().getLong(path + ".salary");
            if (salary == 0 || now < state.data().getLong(path + ".next")) continue;
            UUID member = UUID.fromString(person);
            UUID employer = UUID.fromString(state.data().getString(path + ".nation"));
            if (api.nationById(employer) == null) continue;
            String treasury = nationPath(employer) + ".treasury";
            long funds = state.data().getLong(treasury);
            if (funds < salary) continue;
            try {
                state.transaction(undo -> {
                    api.economy(undo, member, salary, true);
                    state.data().set(treasury, funds - salary);
                    state.data().set(path + ".next", now + PAY_PERIOD);
                });
                Player online = Bukkit.getPlayer(member);
                if (online != null) online.sendMessage("諜報員の月給 " + Input.money(salary) + " を受け取りました。");
            } catch (State.Rejected unavailable) {
                // Leave the due date intact if the economy provider cannot pay this account yet.
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Could not pay spy salary for " + person, failure);
            }
        }
    }
}
