package dev.townyeconomylaw;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Civics.nationPath;
import static dev.townyeconomylaw.State.require;

final class Alliances {
    private final State state;
    private final Integrations api;
    private final Government government;

    Alliances(State state, Integrations api, Government government) {
        this.state = state;
        this.api = api;
        this.government = government;
    }

    void nation(Player actor, String[] raw) {
        List<String> args = Input.arguments(raw);
        switch (action(args, 1)) {
            case "reaty", "treaty" -> treaty(actor, args);
            case "alliance" -> alliance(actor, args);
            case "es" -> sanctions(actor, args);
            default -> throw new State.Rejected("使い方 /tel nation <reaty|alliance|ES>");
        }
    }

    String allianceName(UUID country) {
        String path = membership(country);
        return path == null ? null : data().getString(path + ".name");
    }

    boolean sameAlliance(UUID first, UUID second) {
        String path = membership(first);
        return path != null && path.equals(membership(second));
    }

    boolean dutyFree(UUID first, UUID second) {
        String path = membership(first);
        return path != null && path.equals(membership(second)) && data().getBoolean(path + ".clauses.duty-free");
    }

    boolean sanctioned(UUID first, UUID second) {
        return first != null && second != null && !first.equals(second)
            && (data().getBoolean(sanctionPath(first, second)) || data().getBoolean(sanctionPath(second, first)));
    }

    private String membership(UUID country) {
        if (country == null) return null;
        String id = data().getString(nationPath(country) + ".alliance");
        String path = "alliances." + id;
        return id != null && data().contains(path + ".name") && data().getBoolean(member(path, country)) ? path : null;
    }

    private void treaty(Player actor, List<String> args) {
        String operation = action(args, 2);
        Integrations.Nation ours = government.citizenship(actor);
        Integrations.Nation other = foreignNation(ours, word(args, 3));
        String root = treatyPath(ours.id(), other.id());
        if (operation.equals("list")) {
            require(args.size() == 4, "使い方 /tel nation reaty list <国名>");
            if (state.keys(root).isEmpty()) actor.sendMessage("対象国との条約はありません。");
            for (String id : state.keys(root)) {
                String path = root + "." + id;
                actor.sendMessage(data().getString(path + ".title") + " / " + treatyStatus(path, ours.id()));
            }
            return;
        }
        String title = title(word(args, 4));
        String path = root + "." + key(title);
        if (operation.equals("show")) {
            require(args.size() == 5, "使い方 /tel nation reaty show <国名> <タイトル>");
            require(data().contains(path + ".title"), "その条約が見つかりません。");
            actor.sendMessage(data().getString(path + ".title") + " / " + treatyStatus(path, ours.id()));
            actor.sendMessage(data().getString(path + ".body"));
            return;
        }
        official(actor, ours);
        switch (operation) {
            case "create" -> {
                String body = body(args, 5);
                require(!data().contains(path + ".title"), "相手国との同名の条約・提案が存在します。");
                state.transaction(undo -> {
                    data().set(path + ".title", title);
                    data().set(path + ".body", body);
                    data().set(path + ".proposer", ours.id().toString());
                });
                actor.sendMessage(other.name() + " に条約を提案しました。");
            }
            case "accept" -> {
                require(args.size() == 5, "使い方 /tel nation reaty accept <国名> <タイトル>");
                require(other.id().toString().equals(data().getString(path + ".proposer")) && !data().getBoolean(path + ".active"),
                    "相手国からの承認待ちの提案がありません。");
                state.transaction(undo -> data().set(path + ".active", true));
                actor.sendMessage(other.name() + " と条約を締結しました。");
            }
            case "remove" -> {
                require(args.size() == 5, "使い方 /tel nation reaty remove <国名> <タイトル>");
                require(data().contains(path + ".title"), "その条約が見つかりません。");
                state.transaction(undo -> data().set(path, null));
                actor.sendMessage(other.name() + " との条約・提案を解除しました。");
            }
            default -> throw new State.Rejected("使い方 /tel nation reaty <create|remove|accept|list|show> <国名>");
        }
    }

    private String treatyStatus(String path, UUID own) {
        if (data().getBoolean(path + ".active")) return "締結済み";
        return own.toString().equals(data().getString(path + ".proposer")) ? "提案中" : "承認待ち";
    }

    private static String treatyPath(UUID a, UUID b) {
        return "treaties." + (a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a);
    }

    private void sanctions(Player actor, List<String> args) {
        Integrations.Nation ours = government.citizenship(actor);
        String operation = action(args, 2);
        if (operation.equals("list")) {
            require(args.size() == 3, "使い方 /tel nation ES list");
            List<String> names = countryNames(state.keys(nationPath(ours.id()) + ".sanctions"));
            actor.sendMessage(names.isEmpty() ? "発動中の経済制裁はありません。" : "制裁対象 " + String.join("、", names));
            return;
        }
        official(actor, ours);
        require(args.size() == 4 && (operation.equals("add") || operation.equals("revoke")),
            "使い方 /tel nation ES <add|revoke> <国名>");
        Integrations.Nation other = foreignNation(ours, word(args, 3));
        String path = sanctionPath(ours.id(), other.id());
        boolean enabled = operation.equals("add");
        require(enabled != data().getBoolean(path), enabled ? "その国に経済制裁を発動済みです。" : "解除する経済制裁がありません。");
        state.transaction(undo -> data().set(path, enabled ? true : null));
        actor.sendMessage(other.name() + (enabled ? " への経済制裁を発動しました。" : " への経済制裁を解除しました。"));
    }

    private static String sanctionPath(UUID own, UUID foreign) {
        return nationPath(own) + ".sanctions." + foreign;
    }

    private void alliance(Player actor, List<String> args) {
        Integrations.Nation ours = government.citizenship(actor);
        String operation = action(args, 2);
        String ownAlliance = membership(ours.id());
        if (operation.equals("info") || operation.equals("list")) {
            allianceInfo(actor, args, ownAlliance);
            return;
        }
        if (operation.equals("rules") && action(args, 3).equals("list")) {
            require(ownAlliance != null, "自国は同盟・連合に加盟していません。");
            require(args.size() == 4, "使い方 /tel nation alliance rules list");
            showRules(actor, ownAlliance);
            return;
        }
        official(actor, ours);
        switch (operation) {
            case "create" -> createAlliance(actor, ours.id(), ownAlliance, args);
            case "leave" -> leaveAlliance(actor, ours.id(), ownAlliance, args);
            case "join_request" -> requestJoin(actor, ours.id(), ownAlliance, args);
            default -> {
                require(ownAlliance != null, "自国は同盟・連合に加盟していません。");
                require(ours.id().toString().equals(data().getString(ownAlliance + ".owner")), "盟主国のリーダーまたは大臣のみ実行できます。");
                administerAlliance(actor, ours, ownAlliance, operation, args);
            }
        }
    }

    private void allianceInfo(Player actor, List<String> args, String ownAlliance) {
        if (action(args, 2).equals("list")) {
            require(args.size() == 3, "使い方 /tel nation alliance list");
            List<String> names = state.keys("alliances").stream().map(id -> data().getString("alliances." + id + ".name"))
                .filter(java.util.Objects::nonNull).sorted().toList();
            actor.sendMessage(names.isEmpty() ? "同盟・連合はありません。" : String.join("、", names));
            return;
        }
        require(args.size() <= 4, "使い方 /tel nation alliance info [同盟・連合名]");
        String path = args.size() == 4 ? findAlliance(word(args, 3)) : ownAlliance;
        require(path != null, "自国は同盟・連合に加盟していません。");
        actor.sendMessage(data().getString(path + ".name") + " / 盟主国 "
            + countryName(data().getString(path + ".owner")));
        actor.sendMessage("加盟国 " + String.join("、", countryNames(state.keys(path + ".members"))));
        actor.sendMessage("duty-free " + onOff(path, "duty-free") + " / NBC " + onOff(path, "nbc") + " / ACP " + onOff(path, "acp"));
        List<String> exceptions = countryNames(state.keys(path + ".nbc-exceptions"));
        if (!exceptions.isEmpty()) actor.sendMessage("NBC 例外 " + String.join("、", exceptions));
    }

    private String onOff(String path, String clause) {
        return data().getBoolean(path + ".clauses." + clause) ? "on" : "off";
    }

    private void createAlliance(Player actor, UUID country, String current, List<String> args) {
        require(args.size() == 4, "使い方 /tel nation alliance create <同盟・連合名>");
        require(current == null, "自国はすでに同盟・連合に加盟しています。");
        String name = title(word(args, 3));
        String path = alliancePath(name);
        require(!data().contains(path + ".name"), "同じ名前の同盟・連合が存在します。");
        state.transaction(undo -> {
            data().set(path + ".name", name);
            data().set(path + ".owner", country.toString());
            addMember(path, country);
        });
        actor.sendMessage(name + " を設立しました。");
    }

    private void leaveAlliance(Player actor, UUID country, String current, List<String> args) {
        require(args.size() == 4, "使い方 /tel nation alliance leave <同盟・連合名>");
        String path = findAlliance(word(args, 3));
        require(path.equals(current), "指定の同盟・連合に加盟していません。");
        List<String> remaining = state.keys(path + ".members").stream()
            .filter(id -> !id.equals(country.toString()) && api.nationById(UUID.fromString(id)) != null).sorted().toList();
        String name = data().getString(path + ".name");
        state.transaction(undo -> {
            removeMember(path, country);
            if (remaining.isEmpty()) {
                for (String stale : state.keys(path + ".members")) removeMember(path, UUID.fromString(stale));
                data().set(path, null);
            } else if (country.toString().equals(data().getString(path + ".owner"))) {
                data().set(path + ".owner", remaining.getFirst());
            }
        });
        actor.sendMessage(name + (remaining.isEmpty() ? " を解散しました。" : " から脱退しました。"));
    }

    private void requestJoin(Player actor, UUID country, String current, List<String> args) {
        require(args.size() == 4, "使い方 /tel nation alliance join_request <同盟・連合名>");
        require(current == null, "自国はすでに同盟・連合に加盟しています。");
        String path = findAlliance(word(args, 3));
        require(!data().getBoolean(path + ".bans." + country), "この同盟・連合には加盟申請できません。");
        require(!data().getBoolean(request(path, country)), "加盟申請はすでに審査待ちです。");
        state.transaction(undo -> data().set(request(path, country), true));
        actor.sendMessage(data().getString(path + ".name") + " に加盟を申請しました。");
    }

    private void administerAlliance(Player actor, Integrations.Nation ours, String path, String operation, List<String> args) {
        switch (operation) {
            case "request" -> decideJoin(actor, ours, path, args);
            case "kick", "ban", "unban" -> memberAction(actor, ours, path, operation, args);
            case "set" -> setClause(actor, path, args);
            case "add", "remove" -> nbcException(actor, path, operation, args);
            case "rules" -> changeRule(actor, path, args);
            default -> throw new State.Rejected("使い方 /tel nation alliance <create|leave|join_request|request|kick|ban|set|rules|info|list>");
        }
    }

    private void decideJoin(Player actor, Integrations.Nation ours, String path, List<String> args) {
        String decision = action(args, 3);
        if (decision.equals("list")) {
            require(args.size() == 4, "使い方 /tel nation alliance request list");
            List<String> requests = countryNames(state.keys(path + ".requests"));
            actor.sendMessage(requests.isEmpty() ? "審査待ちの加盟申請はありません。" : "申請国 " + String.join("、", requests));
            return;
        }
        require(args.size() == 5 && (decision.equals("accept") || decision.equals("deny")),
            "使い方 /tel nation alliance request <accept|deny> <国名>");
        Integrations.Nation other = foreignNation(ours, word(args, 4));
        require(data().getBoolean(request(path, other.id())), "その国からの加盟申請がありません。");
        boolean accepted = decision.equals("accept");
        if (accepted) {
            require(membership(other.id()) == null, "対象国はすでに他の同盟・連合に加盟しています。");
            require(!data().getBoolean(path + ".bans." + other.id()), "対象国は加盟禁止です。");
        }
        state.transaction(undo -> {
            data().set(request(path, other.id()), null);
            if (accepted) {
                addMember(path, other.id());
                for (String id : state.keys("alliances")) data().set(request("alliances." + id, other.id()), null);
            }
        });
        actor.sendMessage(other.name() + (accepted ? " の加盟を承認しました。" : " の加盟申請を却下しました。"));
    }

    private void memberAction(Player actor, Integrations.Nation ours, String path, String action, List<String> args) {
        require(args.size() == 4, "使い方 /tel nation alliance <kick|ban|unban> <国名>");
        Integrations.Nation target = foreignNation(ours, word(args, 3));
        UUID country = target.id();
        String banPath = path + ".bans." + country;
        switch (action) {
            case "kick" -> require(path.equals(membership(country)), "その国は加盟していません。");
            case "ban" -> require(!data().getBoolean(banPath), "その国はすでに加盟禁止です。");
            default -> require(data().getBoolean(banPath), "その国は加盟禁止になっていません。");
        }
        state.transaction(undo -> {
            if (!action.equals("unban") && path.equals(membership(country))) removeMember(path, country);
            if (action.equals("ban")) {
                data().set(banPath, true);
                data().set(request(path, country), null);
            } else if (action.equals("unban")) {
                data().set(banPath, null);
            }
        });
        actor.sendMessage(target.name() + switch (action) {
            case "kick" -> " を除名しました。";
            case "ban" -> " を加盟禁止にしました。";
            default -> " の加盟禁止を解除しました。";
        });
    }

    private void setClause(Player actor, String path, List<String> args) {
        require(args.size() == 6 && action(args, 3).equals("clause"),
            "使い方 /tel nation alliance set clause <duty-free|NBC|ACP> <on|off>");
        String clause = action(args, 4);
        String option = action(args, 5);
        require(List.of("duty-free", "nbc", "acp").contains(clause) && List.of("on", "off").contains(option),
            "条項と on または off を指定してください。");
        state.transaction(undo -> data().set(path + ".clauses." + clause, option.equals("on")));
        actor.sendMessage(word(args, 4) + " を " + option + " に設定しました。");
    }

    private void nbcException(Player actor, String path, String operation, List<String> args) {
        require(args.size() == 5 && List.of("nbc_exeption", "nbc_exception").contains(action(args, 3)),
            "使い方 /tel nation alliance <add|remove> NBC_exeption <国名>");
        Integrations.Nation country = namedNation(word(args, 4));
        String entry = path + ".nbc-exceptions." + country.id();
        boolean add = operation.equals("add");
        require(add != data().getBoolean(entry), add ? "対象国はすでに NBC 例外です。" : "指定国の NBC 例外がありません。");
        state.transaction(undo -> data().set(entry, add ? true : null));
        actor.sendMessage(country.name() + (add ? " を NBC 例外に追加しました。" : " の NBC 例外を解除しました。"));
    }

    private void changeRule(Player actor, String path, List<String> args) {
        String operation = action(args, 3);
        String current = title(word(args, 4));
        String root = path + ".rules.";
        String from = root + key(current);
        if (operation.equals("remove")) {
            require(args.size() == 5, "使い方 /tel nation alliance rules remove <タイトル>");
            require(data().contains(from + ".title"), "指定の規則がありません。");
            state.transaction(undo -> data().set(from, null));
            actor.sendMessage("同盟規則を削除しました。");
            return;
        }
        require(operation.equals("create") || operation.equals("change"),
            "使い方 /tel nation alliance rules <create|remove|change> <タイトル>");
        boolean change = operation.equals("change");
        String title = change ? title(word(args, 5)) : current;
        String text = body(args, change ? 6 : 5);
        String to = root + key(title);
        if (change) require(data().contains(from + ".title"), "指定の規則がありません。");
        require(!data().contains(to + ".title") || (change && from.equals(to)), "同じタイトルの同盟規則が存在します。");
        state.transaction(undo -> {
            if (change && !from.equals(to)) data().set(from, null);
            data().set(to + ".title", title);
            data().set(to + ".body", text);
        });
        actor.sendMessage("同盟規則を保存しました。");
    }

    private void showRules(Player actor, String path) {
        String root = path + ".rules";
        if (state.keys(root).isEmpty()) actor.sendMessage("同盟規則はありません。");
        for (String id : state.keys(root)) {
            String rule = root + "." + id;
            actor.sendMessage(data().getString(rule + ".title") + " / " + data().getString(rule + ".body"));
        }
    }

    private void addMember(String path, UUID country) {
        data().set(member(path, country), true);
        data().set(nationPath(country) + ".alliance", path.substring("alliances.".length()));
    }

    private void removeMember(String path, UUID country) {
        data().set(member(path, country), null);
        if (path.endsWith("." + data().getString(nationPath(country) + ".alliance"))) {
            data().set(nationPath(country) + ".alliance", null);
        }
    }

    private static String member(String alliance, UUID country) {
        return alliance + ".members." + country;
    }

    private static String request(String alliance, UUID country) {
        return alliance + ".requests." + country;
    }

    private String findAlliance(String name) {
        String path = alliancePath(name);
        require(data().contains(path + ".name"), "指定の同盟・連合が見つかりません。");
        return path;
    }

    private static String alliancePath(String name) {
        return "alliances." + key(name.strip());
    }

    private static String key(String label) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(label.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private List<String> countryNames(java.util.Set<String> ids) {
        return ids.stream().map(this::countryName).sorted().toList();
    }

    private String countryName(String id) {
        Integrations.Nation nation = api.nationById(UUID.fromString(id));
        return nation != null ? nation.name() : id;
    }

    private Integrations.Nation foreignNation(Integrations.Nation ours, String name) {
        Integrations.Nation other = namedNation(name);
        require(!ours.id().equals(other.id()), "他国を指定してください。");
        return other;
    }

    private Integrations.Nation namedNation(String name) {
        Integrations.Nation nation = api.nation(name);
        require(nation != null, "指定の国が見つかりません。");
        return nation;
    }

    private void official(Player actor, Integrations.Nation nation) {
        UUID id = actor.getUniqueId();
        require(id.equals(nation.leader()) || government.hasRole(nation, id, Government.Role.MINISTER),
            "国のリーダーまたは大臣のみ実行できます。");
    }

    private static String title(String value) {
        String trimmed = value.strip();
        require(!trimmed.isEmpty() && trimmed.length() <= 80, "タイトルは1〜80文字で指定してください。");
        return trimmed;
    }

    private static String body(List<String> args, int start) {
        require(start < args.size(), "内容を指定してください。");
        String body = String.join(" ", args.subList(start, args.size())).strip();
        require(!body.isEmpty() && body.length() <= 1000, "内容は1〜1000文字で指定してください。");
        return body;
    }

    private static String action(List<String> args, int index) {
        return word(args, index).toLowerCase(Locale.ROOT);
    }

    private static String word(List<String> args, int index) {
        require(index < args.size(), "必要な引数を指定してください。");
        return args.get(index);
    }

    private YamlConfiguration data() {
        return state.data();
    }
}
