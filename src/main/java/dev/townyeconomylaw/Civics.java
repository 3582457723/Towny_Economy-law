package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.State.require;

final class Civics {
    enum Document {
        CONSTITUTION("constitution", "憲法"), LAW("statutes", "法律"), REGULATION("regulations", "条例");

        final String path;
        final String label;

        Document(String path, String label) {
            this.path = path;
            this.label = label;
        }
    }

    record Entry(String id, String title, String body, String penalty, String note) {}
    record Party(String code, String name, String owner, int members) {}

    private static final List<String> MDSA = List.of("君主", "一党", "民主", "限定民主", "君民主", "限定民主と君主");
    private static final List<String> EA = List.of("無し", "政党", "限定民主", "民主");
    static final List<String> ELECTION_TYPES = List.of("総選挙", "国事選挙", "議席選挙");

    private final State state;
    private final Integrations api;
    private final Government government;
    private final Territory territory;

    Civics(State state, Integrations api, Government government, Territory territory) {
        this.state = state;
        this.api = api;
        this.government = government;
        this.territory = territory;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    static String nationPath(UUID id) {
        return "nations." + id;
    }

    private String nationPath(Integrations.Nation nation) {
        return nationPath(nation.id());
    }

    private String townsPath(UUID town) {
        return "towns." + town;
    }

    Integrations.Nation leader(Player player) {
        Integrations.Nation nation = government.citizenship(player);
        require(player.getUniqueId().equals(nation.leader()), "国のリーダーのみ実行できます。");
        return nation;
    }

    void setSystem(Player player, String code, String system) {
        Integrations.Nation nation = leader(player);
        List<String> choices = switch (code) {
            case "mdsa" -> MDSA;
            case "ea" -> EA;
            default -> throw new State.Rejected("使い方 /tel nation set <MDSA|EA> <制度>");
        };
        require(choices.contains(system), "制度は " + String.join(" / ", choices) + " から選択してください。");
        state.transaction(undo -> data().set(nationPath(nation) + "." + code, system));
        player.sendMessage(code.toUpperCase(Locale.ROOT) + " を「" + system + "」に設定しました。");
    }

    void changeDocument(Player player, Document type, String action, String selector, List<String> fields) {
        Integrations.Nation nation = type == Document.REGULATION ? null : government.citizenship(player);
        Integrations.Town town = type == Document.REGULATION ? requireTown(player) : null;
        if (town != null) require(player.getUniqueId().equals(town.mayor()), "町長のみ条例を変更できます。");
        String root = (town == null ? nationPath(nation) : townsPath(town.id())) + "." + type.path;
        if (type == Document.LAW && action.equals("remove") && state.keys(nationPath(nation) + ".laws").contains(selector)) {
            changeLegacyLaw(player, selector, null);
            return;
        }
        String id = action.equals("add") ? UUID.randomUUID().toString() : find(root, selector);
        if (!action.equals("remove")) ensureTitle(root, id, fields.getFirst());
        String path = root + "." + id;

        if (nation == null || legislativeDirect(player, nation)) {
            state.transaction(undo -> writeDocument(path, action, fields));
            player.sendMessage(type.label + "を更新しました。 " + id);
            return;
        }

        String system = data().getString(nationPath(nation) + ".mdsa", "君主");
        boolean parliament = system.equals("限定民主") || system.equals("限定民主と君主");
        require(parliament || system.equals("民主") || system.equals("君民主"), "現在の制度で法令を変更する権限がありません。");
        if (parliament) require(politician(nation, player.getUniqueId()), "議案は政治家のみ提出できます。");
        String content = type.label + " " + action + " " + (action.equals("remove") ? data().getString(path + ".title") : fields.getFirst());
        String ballot = start(player, nation, parliament ? "議会議決" : "国民投票", content, 1,
            electorate(nation, parliament ? "限定民主" : "民主"), election -> {
                data().set(election + ".document", type.name());
                data().set(election + ".document-id", id);
                data().set(election + ".action", action);
                data().set(election + ".fields", fields);
                data().set(election + ".expected", data().getString(path + ".revision", ""));
            });
        player.sendMessage("法案を提出しました。投票 ID " + ballot);
    }

    void changeLegacyLaw(Player actor, String id, String text) {
        Integrations.Nation nation = government.citizenship(actor);
        String path = nationPath(nation) + ".laws." + id;
        String before = data().getString(path);
        require(text != null || before != null, "法律が見つかりません。");
        if (legislativeDirect(actor, nation)) {
            state.transaction(undo -> data().set(path, text));
            actor.sendMessage("法律を更新しました。");
            return;
        }
        String system = data().getString(nationPath(nation) + ".mdsa", "君主");
        boolean parliament = system.equals("限定民主") || system.equals("限定民主と君主");
        require(parliament || system.equals("民主") || system.equals("君民主"), "現在の制度で法律を変更する権限がありません。");
        if (parliament) require(politician(nation, actor.getUniqueId()), "議案は政治家のみ提出できます。");
        String ballot = start(actor, nation, parliament ? "議会議決" : "国民投票", "法律 " + id, 1,
            electorate(nation, parliament ? "限定民主" : "民主"), election -> {
                data().set(election + ".legacy-law", id);
                data().set(election + ".legacy-before", before);
                data().set(election + ".legacy-text", text);
            });
        actor.sendMessage("法案を提出しました。投票 ID " + ballot);
    }

    private boolean legislativeDirect(Player player, Integrations.Nation nation) {
        UUID actor = player.getUniqueId();
        boolean leader = actor.equals(nation.leader());
        return switch (data().getString(nationPath(nation) + ".mdsa", "君主")) {
            case "君主", "君民主", "限定民主と君主" -> leader;
            case "一党" -> {
                String party = memberParty(nation, actor);
                yield party != null && party.equals(memberParty(nation, nation.leader()));
            }
            default -> false;
        };
    }

    private void writeDocument(String path, String action, List<String> fields) {
        if (action.equals("remove")) {
            data().set(path, null);
            return;
        }
        data().set(path + ".title", fields.get(0));
        data().set(path + ".body", fields.get(1));
        data().set(path + ".penalty", fields.get(2));
        data().set(path + ".note", fields.get(3));
        data().set(path + ".revision", UUID.randomUUID().toString());
    }

    private String find(String root, String selector) {
        for (String id : state.keys(root)) {
            if (id.equalsIgnoreCase(selector) || selector.equalsIgnoreCase(data().getString(root + "." + id + ".title"))) return id;
        }
        throw new State.Rejected("指定の法令が見つかりません。");
    }

    private void ensureTitle(String root, String target, String title) {
        for (String id : state.keys(root)) {
            require(id.equals(target) || !title.equalsIgnoreCase(data().getString(root + "." + id + ".title")),
                "同じタイトルがすでに登録されています。");
        }
    }

    List<Entry> documents(Player player, Document type) {
        String prefix = type == Document.REGULATION ? townsPath(requireTown(player).id())
            : nationPath(government.citizenship(player));
        String root = prefix + "." + type.path;
        ArrayList<Entry> entries = new ArrayList<>();
        for (String id : state.keys(root)) {
            String path = root + "." + id;
            entries.add(new Entry(id, data().getString(path + ".title", id), data().getString(path + ".body", ""),
                data().getString(path + ".penalty", ""), data().getString(path + ".note", "")));
        }
        if (type == Document.LAW) {
            for (String id : state.keys(prefix + ".laws")) {
                entries.add(new Entry(id, id, data().getString(prefix + ".laws." + id, ""), "", ""));
            }
        }
        return entries.stream().sorted(java.util.Comparator.comparing(Entry::title)).toList();
    }

    private Integrations.Town requireTown(Player player) {
        Integrations.Town town = api.town(player.getUniqueId());
        require(town != null, "Towny の町に所属してください。");
        return town;
    }

    boolean politician(Integrations.Nation nation, UUID player) {
        return government.hasRole(nation, player, Government.Role.POLITICIAN);
    }

    private List<String> electorate(Integrations.Nation nation, String mode) {
        return api.citizens(nation).stream().distinct().filter(id -> switch (mode) {
            case "民主" -> true;
            case "限定民主" -> politician(nation, id);
            case "政党" -> politician(nation, id) && memberParty(nation, id) != null;
            default -> false;
        }).map(UUID::toString).toList();
    }

    void election(Player actor, int days, String type, String content) {
        Integrations.Nation nation = leader(actor);
        require(ELECTION_TYPES.contains(type), "選挙種別は 総選挙 / 国事選挙 / 議席選挙 です。");
        String mode = data().getString(nationPath(nation) + ".ea", "無し");
        String id = start(actor, nation, type, content, days, electorate(nation, mode), path -> {});
        actor.sendMessage(type + " を開始しました。 投票 ID " + id);
    }

    void territorialVote(Player actor, Integrations.Nation nation, String kind, UUID subject, String name) {
        String id = start(actor, nation, "議会議決", name + " の離脱", 1, electorate(nation, "限定民主"), path -> {
            data().set(path + ".territory-kind", kind);
            data().set(path + ".territory-id", subject.toString());
            data().set(path + ".territory-registration", territory.registration(kind, subject));
        });
        actor.sendMessage("離脱の議案を開始しました。 投票 ID " + id);
    }

    private String start(Player player, Integrations.Nation nation, String type, String content,
                         int days, List<String> voters, java.util.function.Consumer<String> extra) {
        require(!voters.isEmpty(), "現在の設定で投票資格を持つ住民がいません。");
        String id = UUID.randomUUID().toString();
        String path = electionPath(nation.id(), id);
        World world = player.getWorld();
        state.transaction(undo -> {
            data().set(path + ".type", type);
            data().set(path + ".content", content);
            data().set(path + ".world", world.getUID().toString());
            data().set(path + ".end", Math.addExact(world.getFullTime(), 24_000L * days));
            data().set(path + ".electorate", voters);
            data().set(path + ".status", "受付中");
            extra.accept(path);
        });
        return id;
    }

    private String electionPath(UUID nation, String id) {
        return nationPath(nation) + ".elections." + id;
    }

    private String openElection(Player player, String id) {
        Integrations.Nation nation = government.citizenship(player);
        String path = electionPath(nation.id(), id);
        require("受付中".equals(data().getString(path + ".status")), "受付中の選挙が見つかりません。");
        require(!expired(path), "投票期間は終了しています。");
        return path;
    }

    private boolean expired(String path) {
        World world = Bukkit.getWorld(UUID.fromString(data().getString(path + ".world")));
        return world != null && world.getFullTime() >= data().getLong(path + ".end");
    }

    void stand(Player player, String id) {
        String path = openElection(player, id);
        require(candidateElection(path), "この投票は賛否投票です。");
        state.transaction(undo -> data().set(path + ".candidates." + player.getUniqueId(), player.getName()));
        player.sendMessage("立候補を受け付けました。");
    }

    void vote(Player player, String id, String selection) {
        String path = openElection(player, id);
        String voter = player.getUniqueId().toString();
        require(data().getStringList(path + ".electorate").contains(voter), "この選挙の投票権がありません。");
        String choice = selection.toLowerCase(Locale.ROOT);
        if (candidateElection(path)) {
            choice = state.keys(path + ".candidates").stream()
                .filter(candidate -> selection.equalsIgnoreCase(data().getString(path + ".candidates." + candidate)))
                .findFirst().orElseThrow(() -> new State.Rejected("立候補者が見つかりません。"));
        } else {
            require(choice.equals("yes") || choice.equals("no"), "賛成は yes、反対は no を指定してください。");
        }
        String ballot = choice;
        state.transaction(undo -> data().set(path + ".votes." + voter, ballot));
        player.sendMessage("投票を受け付けました。締切までは変更できます。");
    }

    void elections(Player player, String id) {
        Integrations.Nation nation = government.citizenship(player);
        String root = nationPath(nation) + ".elections";
        if (id != null) {
            String path = electionPath(nation.id(), id);
            require(data().contains(path), "選挙が見つかりません。");
            player.sendMessage(electionSummary(path, id));
            List<String> fields = data().getStringList(path + ".fields");
            if (fields.size() == 4) {
                player.sendMessage(fields.get(1));
                if (!fields.get(2).isEmpty()) player.sendMessage("罰則 " + fields.get(2));
                if (!fields.get(3).isEmpty()) player.sendMessage("追記 " + fields.get(3));
            }
            String legacy = data().getString(path + ".legacy-text");
            if (legacy != null) player.sendMessage(legacy);
            List<String> names = state.keys(path + ".candidates").stream()
                .map(candidate -> data().getString(path + ".candidates." + candidate)).sorted().toList();
            if (!names.isEmpty()) player.sendMessage("候補 " + String.join(", ", names));
            return;
        }
        if (state.keys(root).isEmpty()) player.sendMessage("選挙はありません。");
        for (String ballot : state.keys(root)) player.sendMessage(electionSummary(root + "." + ballot, ballot));
    }

    private String electionSummary(String path, String id) {
        return id + " " + data().getString(path + ".type") + " / " + data().getString(path + ".content")
            + " / " + data().getString(path + ".status");
    }

    private boolean candidateElection(String path) {
        String type = data().getString(path + ".type");
        return "総選挙".equals(type) || "議席選挙".equals(type);
    }

    void closeElections() {
        for (String country : state.keys("nations")) {
            String root = "nations." + country + ".elections";
            for (String id : state.keys(root)) {
                String path = root + "." + id;
                if ("受付中".equals(data().getString(path + ".status")) && expired(path)) {
                    finish(UUID.fromString(country), path);
                }
            }
        }
    }

    private void finish(UUID country, String path) {
        Map<String, Integer> tally = new HashMap<>();
        for (String id : state.keys(path + ".votes")) {
            tally.merge(data().getString(path + ".votes." + id), 1, Integer::sum);
        }
        state.transaction(undo -> {
            if (candidateElection(path)) {
                int best = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                List<String> winners = tally.entrySet().stream().filter(vote -> vote.getValue() == best)
                    .map(Map.Entry::getKey).toList();
                String result = "当選者なし";
                if (winners.size() == 1) {
                    String winner = winners.getFirst();
                    Integrations.Nation membership = api.nation(UUID.fromString(winner));
                    if (membership != null && country.equals(membership.id())) {
                        result = "当選 " + data().getString(path + ".candidates." + winner) + " (" + best + "票)";
                        if ("議席選挙".equals(data().getString(path + ".type"))) {
                            data().set(nationPath(country) + ".roles." + winner + ".politician", true);
                        }
                    }
                }
                data().set(path + ".status", result);
                return;
            }
            int yes = tally.getOrDefault("yes", 0);
            int no = tally.getOrDefault("no", 0);
            String result = yes > no ? applyDecision(country, path) : "否決";
            data().set(path + ".status", result + "（賛成 " + yes + " / 反対 " + no + "）");
        });
    }

    private String applyDecision(UUID country, String ballot) {
        String legacy = data().getString(ballot + ".legacy-law");
        if (legacy != null) {
            String path = nationPath(country) + ".laws." + legacy;
            if (!java.util.Objects.equals(data().getString(path), data().getString(ballot + ".legacy-before"))) {
                return "可決・対象変更済み";
            }
            data().set(path, data().getString(ballot + ".legacy-text"));
        }
        String document = data().getString(ballot + ".document");
        if (document != null) {
            Document type = Document.valueOf(document);
            String root = nationPath(country) + "." + type.path;
            String id = data().getString(ballot + ".document-id");
            String path = root + "." + id;
            if (!data().getString(path + ".revision", "").equals(data().getString(ballot + ".expected"))) return "可決・対象変更済み";
            String action = data().getString(ballot + ".action");
            List<String> fields = data().getStringList(ballot + ".fields");
            if (!"remove".equals(action)) {
                try {
                    ensureTitle(root, id, fields.getFirst());
                } catch (State.Rejected conflict) {
                    return "可決・同名法令が存在";
                }
            }
            writeDocument(path, action, fields);
        }
        String kind = data().getString(ballot + ".territory-kind");
        if (kind != null) {
            UUID subject = UUID.fromString(data().getString(ballot + ".territory-id"));
            if (!territory.registration(kind, subject).equals(data().getString(ballot + ".territory-registration", ""))) {
                return "可決・対象変更済み";
            }
            try {
                territory.applyDeparture(country, kind, subject);
            } catch (State.Rejected rejected) {
                return "可決・実行不可 " + rejected.getMessage();
            }
        }
        return "可決";
    }

    List<Party> parties(Player actor) {
        String root = nationPath(government.citizenship(actor)) + ".parties";
        return state.keys(root).stream().sorted().map(code -> {
            String path = root + "." + code;
            return new Party(code, data().getString(path + ".name"), data().getString(path + ".owner"),
                state.keys(path + ".members").size());
        }).toList();
    }

    void createParty(Player player, String name, String code, List<UUID> invites) {
        Integrations.Nation nation = government.citizenship(player);
        require(code.matches("[A-Z]{1,4}"), "政党コードは大文字の英字1〜4文字です。");
        require(memberParty(nation, player.getUniqueId()) == null, "すでに別の政党に所属しています。");
        String root = nationPath(nation) + ".parties";
        require(!data().contains(root + "." + code), "政党コードは使用済みです。");
        require(parties(player).stream().noneMatch(party -> party.name().equalsIgnoreCase(name) || party.code().equalsIgnoreCase(name)
            || party.name().equalsIgnoreCase(code)), "政党名またはコードが重複しています。");
        for (UUID invite : invites) validateInvite(nation, invite);
        String path = root + "." + code;
        state.transaction(undo -> {
            data().set(path + ".name", name);
            data().set(path + ".owner", player.getUniqueId().toString());
            data().set(path + ".members." + player.getUniqueId(), true);
            for (UUID member : invites) data().set(path + ".invites." + member, true);
        });
        player.sendMessage("政党を作成しました。 " + code);
    }

    void partyAction(Player player, String action, String selector) {
        Integrations.Nation nation = government.citizenship(player);
        Party party = parties(player).stream().filter(candidate -> candidate.code().equalsIgnoreCase(selector)
            || candidate.name().equalsIgnoreCase(selector)).findFirst()
            .orElseThrow(() -> new State.Rejected("政党が見つかりません。"));
        String path = nationPath(nation) + ".parties." + party.code();
        UUID playerId = player.getUniqueId();
        if (action.equals("remove")) {
            require(playerId.toString().equals(party.owner()), "政党代表のみ解散できます。");
            state.transaction(undo -> data().set(path, null));
        } else {
            require(memberParty(nation, playerId) == null, "すでに政党に所属しています。");
            require(data().getBoolean(path + ".invites." + playerId), "この政党からの招待がありません。");
            state.transaction(undo -> {
                data().set(path + ".members." + playerId, true);
                data().set(path + ".invites." + playerId, null);
            });
        }
        player.sendMessage("政党情報を更新しました。");
    }

    void invite(Player player, UUID member) {
        Integrations.Nation nation = government.citizenship(player);
        String code = memberParty(nation, player.getUniqueId());
        require(code != null, "政党に所属してください。");
        String path = nationPath(nation) + ".parties." + code;
        require(player.getUniqueId().toString().equals(data().getString(path + ".owner")), "政党代表のみ招待できます。");
        validateInvite(nation, member);
        state.transaction(undo -> data().set(path + ".invites." + member, true));
        player.sendMessage("政党へ招待しました。 " + code);
    }

    void leaveParty(Player player) {
        Integrations.Nation nation = government.citizenship(player);
        UUID id = player.getUniqueId();
        String code = memberParty(nation, id);
        require(code != null, "政党に所属していません。");
        String path = nationPath(nation) + ".parties." + code;
        require(!id.toString().equals(data().getString(path + ".owner")), "代表は政党の解散を実行してください。");
        state.transaction(undo -> data().set(path + ".members." + id, null));
        player.sendMessage("政党を離脱しました。");
    }

    private void validateInvite(Integrations.Nation nation, UUID member) {
        Integrations.Nation membership = api.nation(member);
        require(membership != null && nation.id().equals(membership.id()) && memberParty(nation, member) == null,
            "政党に所属していない同じ国の住民のみ招待できます。");
    }

    private String memberParty(Integrations.Nation nation, UUID member) {
        if (member == null) return null;
        String root = nationPath(nation) + ".parties";
        return state.keys(root).stream().filter(code -> data().getBoolean(root + "." + code + ".members." + member))
            .findFirst().orElse(null);
    }
}
