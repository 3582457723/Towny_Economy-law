package dev.townyeconomylaw;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Input.knownPlayer;
import static dev.townyeconomylaw.State.require;

final class EnterpriseCommands {
    private final Enterprises firms;
    private final EnterpriseLots lots;
    private final Transit transit;
    private final TransitMenu menu;
    private final TransitGates gates;
    private final StationRails stationRails;
    private final EnterpriseLoans loans;

    EnterpriseCommands(Enterprises firms, EnterpriseLots lots, Transit transit, TransitMenu menu, TransitGates gates,
                       StationRails stationRails, EnterpriseLoans loans) {
        this.firms = firms;
        this.lots = lots;
        this.transit = transit;
        this.menu = menu;
        this.gates = gates;
        this.stationRails = stationRails;
        this.loans = loans;
    }

    void execute(Player player, Enterprises.Kind kind, String[] input) {
        List<String> args = Input.arguments(input);
        String action = args.isEmpty() ? "list" : action(args, 0);
        switch (action) {
            case "create" -> firms.create(player, kind, name(args, 1), code(args, 2));
            case "remove" -> firms.remove(player, firms.byCode(code(args, 2), kind), name(args, 1));
            case "edit" -> firms.edit(player, firms.byCode(code(args, 1), kind), name(args, 2), code(args, 3));
            case "leave" -> firms.leave(player, firms.byCode(code(args, 1), kind));
            case "join" -> firms.join(player, firms.byCode(code(args, 1), kind));
            case "use" -> firms.use(player, firms.byCode(code(args, 1), kind));
            case "list" -> {
                List<Enterprises.Firm> memberships = firms.memberships(player, kind);
                if (memberships.isEmpty()) player.sendMessage("所属する組織がありません。");
                memberships.forEach(firm -> player.sendMessage(firm.code() + " / " + firm.name()));
            }
            case "help" -> player.sendMessage("操作 create / remove / edit / leave / list / use / invite / join / transfer / plot / train / station / machine / settlement / gate / collect / set interest / loan");
            default -> operate(player, firms.active(player, kind), action, args);
        }
    }

    private void operate(Player player, Enterprises.Firm firm, String action, List<String> args) {
        switch (action) {
            case "invite" -> firms.invite(player, firm, knownPlayer(arg(args, 1)).getUniqueId());
            case "transfer" -> firms.transfer(player, firm, knownPlayer(arg(args, 1)).getUniqueId());
            case "plot" -> plot(player, firm, args);
            case "train" -> {
                require(action(args, 1).equals("route"), "使い方 train route <create|add> <路線名> [駅,...]");
                switch (action(args, 2)) {
                    case "create" -> transit.createRoute(player, firm, name(args, 3));
                    case "add" -> transit.addStops(player, firm, name(args, 3), Arrays.stream(arg(args, 4).split(",", -1))
                        .map(String::strip).map(this::checkedName).toList());
                    default -> throw new State.Rejected("路線の操作は create または add です。");
                }
            }
            case "station" -> station(player, firm, args);
            case "machine", "settlement" -> {
                String operation = action(args, 1);
                require(operation.equals("set") || operation.equals("remove"), "使い方 <machine|settlement> <set|remove>");
                Transit.MachineType type = action.equals("settlement") ? Transit.MachineType.SETTLEMENT : Transit.MachineType.TICKET;
                if (type == Transit.MachineType.TICKET) {
                    transit.configureMachine(player, firm, player.getTargetBlockExact(5), operation.equals("set"));
                } else {
                    transit.configureMachine(player, firm, player.getTargetBlockExact(5), operation.equals("set"), type);
                }
            }
            case "gate" -> {
                String operation = action(args, 1);
                require(operation.equals("set") || operation.equals("remove"), "使い方 gate set [both|entry|exit] または gate remove");
                String type = operation.equals("set") ? (args.size() > 2 ? action(args, 2) : "both") : operation;
                require(!operation.equals("set") || List.of("both", "entry", "exit").contains(type),
                    "改札の種別は both / entry / exit です。");
                gates.configure(player, firm, type);
            }
            case "collect" -> firms.collect(player, firm);
            case "set" -> {
                require(action(args, 1).equals("interest"), "使い方 set interest <0〜100%>");
                loans.setRate(player, firm, arg(args, 2));
            }
            case "loan" -> {
                switch (action(args, 1)) {
                    case "fund" -> loans.fund(player, firm, amount(arg(args, 2)));
                    case "withdraw" -> loans.withdraw(player, firm, amount(arg(args, 2)));
                    case "info" -> loans.lenderInfo(player, firm);
                    default -> throw new State.Rejected("使い方 loan <fund|withdraw|info>");
                }
            }
            default -> throw new State.Rejected("不明な操作です。 /company help または /corporation help");
        }
    }

    private void station(Player player, Enterprises.Firm firm, List<String> args) {
        String operation = action(args, 1);
        if (operation.equals("rail")) {
            String railAction = action(args, 2);
            require(railAction.equals("set") || railAction.equals("remove"), "使い方 station rail set [秒] または station rail remove");
            int seconds = railAction.equals("remove") ? 0 : args.size() > 3 ? Input.number(arg(args, 3), 1, StationRails.MAX_SECONDS) : StationRails.DEFAULT_SECONDS;
            stationRails.configure(player, firm, StationRails.target(player), seconds);
            return;
        }
        require(operation.equals("set") && action(args, 2).equals("price"), "使い方 station set price <開始駅> <終了駅> <価格>");
        transit.fare(player, firm, name(args, 3), name(args, 4), Input.cents(arg(args, 5), 0, Input.MAX_CASH));
    }

    private void plot(Player player, Enterprises.Firm firm, List<String> args) {
        switch (action(args, 1)) {
            case "claim" -> lots.claim(player, firm);
            case "unclaim" -> lots.unclaim(player, firm);
            case "add_lot" -> lots.decide(player, firm, true);
            case "reject_lo", "reject_lot" -> lots.decide(player, firm, false);
            case "lot" -> {
                switch (action(args, 2)) {
                    case "set" -> {
                        String type = action(args, 3);
                        String name = args.size() > 4 ? name(args, 4) : "";
                        require(!type.equals("name") || !name.isEmpty(), "ロット名を指定してください。");
                        lots.configure(player, firm, type, name);
                    }
                    case "share" -> lots.share(player, firm, firms.byCode(code(args, 3)), name(args, 4));
                    case "show" -> {
                        EnterpriseLots.Claim claim = lots.at(player.getLocation());
                        require(claim != null && lots.accessible(claim, firm), "利用できるロットがありません。");
                        EnterpriseLots.Lot lot = claim.lot();
                        player.sendMessage(claim.firm().code() + " / " + lot.name() + " / " + lot.type() + " " + lot.station());
                    }
                    default -> throw new State.Rejected("使い方 plot lot <set|share|show>");
                }
            }
            default -> throw new State.Rejected("使い方 plot <claim|unclaim|add_lot|reject_lot|lot>");
        }
    }

    void routeMap(Player player, String code) {
        menu.routeMap(player, firms.byCode(checkedCode(code)));
    }

    void ic(Player player, String[] args) {
        switch (Input.value(args, 1).toLowerCase(Locale.ROOT)) {
            case "balance" -> transit.balance(player);
            case "charge" -> {
                var block = player.getTargetBlockExact(5);
                require(block != null && transit.machine(block) != null, "券売機または精算機を見て実行してください。");
                transit.charge(player, Input.cents(Input.value(args, 2), 1, Input.MAX_CASH));
            }
            default -> throw new State.Rejected("使い方 /tel ic <balance|charge> [金額]");
        }
    }

    void ticket(Player player, String[] args) {
        require(Input.value(args, 1).equalsIgnoreCase("use"), "使い方 /tel ticket use");
        transit.useTicket(player);
    }

    void borrow(Player player, String[] args) {
        String operation = Input.value(args, 1);
        if (operation.equalsIgnoreCase("info")) loans.borrowerInfo(player);
        else loans.borrow(player, firms.byCode(checkedCode(operation)), amount(Input.value(args, 2)));
    }

    void repay(Player player, String[] args) {
        loans.repay(player, firms.byCode(checkedCode(Input.value(args, 1))), amount(Input.value(args, 2)));
    }

    private long amount(String value) {
        return Input.cents(value, 1, Input.MAX_CASH);
    }

    private String code(List<String> args, int index) {
        return checkedCode(arg(args, index));
    }

    private String checkedCode(String code) {
        require(code.matches("[A-Z]{4}"), "コードは大文字の英字4文字です。");
        return code;
    }

    private String name(List<String> args, int index) {
        return checkedName(arg(args, index));
    }

    private String checkedName(String name) {
        require(!name.isBlank() && name.length() <= 60, "名前は1〜60文字です。");
        return name.strip();
    }

    private String action(List<String> args, int index) {
        return arg(args, index).toLowerCase(Locale.ROOT);
    }

    private String arg(List<String> args, int index) {
        require(index < args.size(), "引数が足りません。");
        return args.get(index);
    }
}
