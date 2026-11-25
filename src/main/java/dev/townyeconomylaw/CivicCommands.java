package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Input.knownPlayer;
import static dev.townyeconomylaw.Input.number;
import static dev.townyeconomylaw.Input.value;
import static dev.townyeconomylaw.State.require;

final class CivicCommands {
    private final Civics civics;
    private final Government government;
    private final Territory territory;
    private final CivicMenu menu;
    private final ElectionMenu electionMenu;
    private final PartyFinance partyFinance;
    private final Diplomacy diplomacy;

    CivicCommands(Civics civics, Government government, Territory territory, CivicMenu menu,
                  ElectionMenu electionMenu, PartyFinance partyFinance, Diplomacy diplomacy) {
        this.civics = civics;
        this.government = government;
        this.territory = territory;
        this.menu = menu;
        this.electionMenu = electionMenu;
        this.partyFinance = partyFinance;
        this.diplomacy = diplomacy;
    }

    void nation(Player player, String[] input) {
        List<String> args = Input.arguments(input);
        String operation = action(args, 1);
        switch (operation) {
            case "set" -> {
                String system = action(args, 2);
                if (system.equals("pfpp")) partyFinance.setGrant(player, Input.cents(argument(args, 3), 0, Input.MAX_CASH));
                else civics.setSystem(player, system, argument(args, 3));
            }
            case "passport", "visa", "tariffs", "fta", "border" -> diplomacy.nation(player, input);
            case "impeachment", "no_confidence" -> {
                require(action(args, 2).equals("start"),
                    "使い方 /tel nation <impeachment|no_confidence> start <後任のMCID> [理由]");
                String reason = args.size() > 4 ? limited(String.join(" ", args.subList(4, args.size())), 240) : "";
                civics.removalMotion(player, knownPlayer(argument(args, 3)), operation.equals("impeachment"), reason);
            }
            case "constitution" -> document(player, Civics.Document.CONSTITUTION, args);
            case "law" -> document(player, Civics.Document.LAW, args);
            case "election" -> election(player, args);
            case "puppet" -> {
                switch (action(args, 2)) {
                    case "join" -> territory.joinPuppet(player, argument(args, 3));
                    case "rebel" -> territory.rebel(player, "puppet");
                    case "release" -> territory.departure(player, "puppet", argument(args, 3), civics);
                    case "status" -> territory.status(player);
                    default -> throw new State.Rejected("使い方 /tel nation puppet <join|rebel|release|status>");
                }
            }
            default -> throw new State.Rejected("使い方 /tel nation <set|constitution|law|election|impeachment|no_confidence|passport|visa|tariffs|fta|border|puppet>");
        }
    }

    void town(Player player, String[] input) {
        List<String> args = Input.arguments(input);
        require(action(args, 1).equals("regulations"), "使い方 /tel town regulations <add|remove|edit>");
        document(player, Civics.Document.REGULATION, args);
    }

    void job(Player player, String[] args) {
        require(value(args, 1).equalsIgnoreCase("set"), "使い方 /tel job set <MCID> <judge|prosecutor|policeman|politician>");
        Government.Role role = switch (value(args, 3).toLowerCase(Locale.ROOT)) {
            case "judge" -> Government.Role.JUDGE;
            case "prosecutor" -> Government.Role.PROSECUTOR;
            case "policeman" -> Government.Role.POLICEMAN;
            case "politician" -> Government.Role.POLITICIAN;
            default -> throw new State.Rejected("役職は judge / prosecutor / policeman / politician です。");
        };
        government.grant(player, knownPlayer(value(args, 2)).getUniqueId(), role, true);
        player.sendMessage("役職を任命しました。");
    }

    void plot(Player player, String[] input) {
        List<String> args = Input.arguments(input);
        String operation = action(args, 1);
        if (operation.equals("set")) {
            Government.Plot type = switch (action(args, 2)) {
                case "jail" -> Government.Plot.JAIL;
                case "courthouse", "courtroom" -> Government.Plot.COURTHOUSE;
                case "police" -> Government.Plot.POLICE;
                case "legislature" -> Government.Plot.LEGISLATURE;
                default -> throw new State.Rejected("プロット種別は jail / courthouse / police / legislature です。");
            };
            government.setPlot(player, type);
            player.sendMessage("現在地のプロット種別を " + type.name().toLowerCase(Locale.ROOT) + " に設定しました。");
            return;
        }
        if (operation.equals("show")) {
            player.sendMessage("現在地のプロット種別 " + government.plotType(player));
            return;
        }
        switch (operation) {
            case "puppet" -> {
                switch (action(args, 2)) {
                    case "create" -> territory.createPuppet(player, argument(args, 3), knownPlayer(argument(args, 4)));
                    case "add" -> territory.invitePuppet(player, argument(args, 3));
                    case "annex" -> territory.annex(player, argument(args, 3));
                    default -> throw new State.Rejected("使い方 /tel plot puppet <create|annex|add>");
                }
            }
            case "decentralization" -> {
                switch (action(args, 2)) {
                    case "create" -> territory.createLocal(player, argument(args, 3), knownPlayer(argument(args, 4)));
                    case "add" -> territory.addLocal(player, argument(args, 3));
                    case "remove" -> territory.departure(player, "local", argument(args, 3), civics);
                    case "rebel" -> territory.rebel(player, "local");
                    default -> throw new State.Rejected("使い方 /tel plot decentralization <create|add|remove|rebel>");
                }
            }
            default -> throw new State.Rejected("使い方 /tel plot <set|show|puppet|decentralization>");
        }
    }

    void party(Player player, String[] input) {
        List<String> args = Input.arguments(input);
        switch (action(args, 1)) {
            case "gui" -> menu.open(player, "政党", civics.parties(player).stream()
                .map(party -> new CivicMenu.Row(Material.PAPER, party.name() + " [" + party.code() + "]",
                    List.of("メンバー " + party.members() + " 人", "招待済みの場合 /tel political_party join " + party.code())))
                .toList());
            case "create" -> {
                List<UUID> invites = new ArrayList<>();
                if (args.size() > 4) {
                    for (String name : argument(args, 4).split(",", -1)) {
                        invites.add(knownPlayer(name.strip()).getUniqueId());
                    }
                }
                civics.createParty(player, limited(argument(args, 2), 48), argument(args, 3), invites);
            }
            case "remove", "join" -> civics.partyAction(player, action(args, 1), argument(args, 2));
            case "add" -> civics.invite(player, knownPlayer(argument(args, 2)).getUniqueId());
            case "leave" -> civics.leaveParty(player);
            case "bank" -> partyFinance.bank(player);
            case "deposit" -> partyFinance.deposit(player, Input.cents(argument(args, 2), 1, Input.MAX_CASH));
            case "withdrawal" -> partyFinance.withdrawal(player, Input.cents(argument(args, 2), 1, Input.MAX_CASH));
            case "history" -> partyFinance.history(player, args.size() > 2 ? number(argument(args, 2), 1, 50) : 10);
            case "grant" -> partyFinance.grant(player);
            default -> throw new State.Rejected("使い方 /tel political_party <create|remove|join|add|leave|GUI|bank|deposit|withdrawal|history|grant>");
        }
    }

    void gui(Player player, Civics.Document type) {
        menu.open(player, type.label, civics.documents(player, type).stream().map(entry -> {
            ArrayList<String> lines = new ArrayList<>();
            lines.add("ID " + entry.id());
            lines.add(entry.body());
            if (!entry.penalty().isEmpty()) lines.add("罰則 " + entry.penalty());
            if (!entry.note().isEmpty()) lines.add("追記 " + entry.note());
            return new CivicMenu.Row(Material.WRITTEN_BOOK, entry.title(), lines);
        }).toList());
    }

    private void document(Player player, Civics.Document type, List<String> args) {
        String action = action(args, 2);
        require(action.equals("add") || action.equals("remove") || (action.equals("edit") && type == Civics.Document.REGULATION),
            "法令操作は add / remove（条例は edit も可）です。");
        String selector = action.equals("add") ? "" : argument(args, 3);
        if (action.equals("remove")) {
            civics.changeDocument(player, type, action, selector, List.of());
            return;
        }
        int offset = action.equals("edit") ? 4 : 3;
        String title = limited(argument(args, offset), 80);
        String body = limited(argument(args, offset + 1), 400);
        boolean penalty = type != Civics.Document.CONSTITUTION;
        String sanctions = penalty ? optional(args, offset + 2) : "";
        String note = optional(args, offset + (penalty ? 3 : 2));
        civics.changeDocument(player, type, action, selector, List.of(title, body, sanctions, note));
    }

    private void election(Player player, List<String> args) {
        switch (action(args, 2)) {
            case "gui" -> electionMenu.open(player);
            case "start" -> civics.election(player, number(argument(args, 3), 1, 365), argument(args, 4),
                limited(String.join(" ", args.subList(5, args.size())), 300));
            case "list" -> civics.elections(player, null);
            case "show" -> civics.elections(player, id(argument(args, 3)));
            case "stand" -> civics.stand(player, id(argument(args, 3)));
            case "vote" -> civics.vote(player, id(argument(args, 3)), argument(args, 4));
            default -> throw new State.Rejected("使い方 /tel nation election <start|list|show|stand|vote>");
        }
    }

    private String optional(List<String> args, int index) {
        return index >= args.size() || args.get(index).equals("-") ? "" : limited(args.get(index), 300);
    }

    private String limited(String value, int limit) {
        require(!value.isBlank() && value.length() <= limit, "入力は 1〜" + limit + " 文字です。");
        return value;
    }

    private String id(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new State.Rejected("投票 ID が無効です。");
        }
    }

    private String action(List<String> args, int index) {
        return argument(args, index).toLowerCase(Locale.ROOT);
    }

    private String argument(List<String> args, int index) {
        require(index < args.size(), "引数が足りません。 /tel help civics");
        return args.get(index);
    }


}
