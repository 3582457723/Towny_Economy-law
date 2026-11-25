package dev.townyeconomylaw;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import static dev.townyeconomylaw.Input.cents;
import static dev.townyeconomylaw.Input.key;
import static dev.townyeconomylaw.Input.knownPlayer;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.Input.number;
import static dev.townyeconomylaw.Input.text;
import static dev.townyeconomylaw.Input.value;
import static dev.townyeconomylaw.State.require;

public class TownyEconomyLaw extends JavaPlugin {
    private Integrations api;
    private Market market;
    private Banks banks;
    private Auctions auctions;
    private Government government;
    private JailGuard jail;
    private Menus menus;
    private ShopMenu shopMenu;
    private Civics civics;
    private CivicCommands civicCommands;
    private EnterpriseCommands enterpriseCommands;
    private StationRails stationRails;
    private MinecartCouplers couplers;
    private TransitRecipes transitRecipes;
    private Diplomacy diplomacy;
    private ElectionMenu electionMenu;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            State state = new State(getDataFolder().toPath(), getLogger());
            api = new Integrations();
            market = new Market(state, api);
            banks = new Banks(state, api);
            government = new Government(state, api);
            diplomacy = new Diplomacy(state, api, government);
            auctions = new Auctions(state, api, diplomacy);
            getServer().getPluginManager().registerEvents(diplomacy, this);
            jail = new JailGuard(government);
            Enterprises firms = new Enterprises(state, api);
            EnterpriseLots lots = new EnterpriseLots(state, api, firms);
            Transit transit = new Transit(this, state, api, firms, lots);
            TransitMenu transitMenu = new TransitMenu(this, government, transit);
            TransitGates gates = new TransitGates(this, state, government, firms, lots, transit);
            couplers = new MinecartCouplers(this, government, api);
            transitRecipes = new TransitRecipes(this, couplers);
            transitRecipes.register();
            stationRails = new StationRails(state, lots, firms, couplers);
            couplers.setHeldCheck(stationRails::held);
            EnterpriseLoans loans = new EnterpriseLoans(state, api, firms);
            enterpriseCommands = new EnterpriseCommands(firms, lots, transit, transitMenu, gates, stationRails, loans);
            getServer().getPluginManager().registerEvents(transitMenu, this);
            getServer().getPluginManager().registerEvents(gates, this);
            getServer().getPluginManager().registerEvents(couplers, this);
            getServer().getPluginManager().registerEvents(stationRails, this);
            getServer().getScheduler().runTaskTimer(this, stationRails::tick, 1L, 1L);
            Territory territory = new Territory(state, api, government);
            api.guardLocalDepartures(this, territory::registeredLocal);
            civics = new Civics(state, api, government, territory);
            CivicMenu civicMenu = new CivicMenu(government);
            electionMenu = new ElectionMenu(this, civics, government);
            PartyFinance partyFinance = new PartyFinance(state, api, civics, government);
            civicCommands = new CivicCommands(civics, government, territory, civicMenu, electionMenu, partyFinance, diplomacy);
            getServer().getPluginManager().registerEvents(electionMenu, this);
            getServer().getPluginManager().registerEvents(civicMenu, this);
            getServer().getScheduler().runTaskTimer(this, civics::closeElections, 1L, 20L);
            long refresh = Math.clamp(getConfig().getLong("market-refresh-ms", 125), 50, 60_000);
            menus = new Menus(this, market, auctions, government, refresh);
            shopMenu = new ShopMenu(this, api, government);
            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(shopMenu, this);
            getServer().getPluginManager().registerEvents(jail, this);
            getServer().getScheduler().runTaskTimer(this, menus::tick, 1L, 1L);
            getServer().getScheduler().runTaskTimer(this, auctions::finishExpired, 1L, 20L);
            getServer().getScheduler().runTaskTimer(this, jail::enforce, 1L, 10L);
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "TownyEconomyLaw could not start", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (stationRails != null) stationRails.releaseAll();
        if (transitRecipes != null) transitRecipes.unregister();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (command.getName().equals("tel") && args.length == 3
                    && action(args, 0).equals("jail") && action(args, 1).equals("release")) {
                run(sender, () -> {
                    government.release(sender, knownPlayer(args[2]));
                    sender.sendMessage("拘禁を解除しました。");
                });
            } else {
                sender.sendMessage("ゲーム内のプレイヤーが実行してください。");
            }
            return true;
        }
        run(player, () -> {
            require(!government.jailed(player), "拘禁中はコマンドを使用できません。");
            if (command.getName().equals("shopaverage")) {
                if (args.length == 0) {
                    shopMenu.open(player);
                } else {
                    require(args.length == 1 && args[0].equalsIgnoreCase("hand"), "使い方 /shopavg [hand]");
                    List<Integrations.ShopPrices> prices = api.prices(player);
                    require(!prices.isEmpty(), "このワールドに一致するショップがありません。");
                    prices.forEach(price -> player.sendMessage(price.display()));
                }
            } else if (command.getName().equals("election")) {
                require(args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("gui")), "使い方 /election GUI");
                electionMenu.open(player);
            } else if (command.getName().equals("request")) {
                diplomacy.request(player, args);
            } else if (command.getName().equals("company") || command.getName().equals("corporation")) {
                enterpriseCommands.execute(player, Enterprises.Kind.valueOf(command.getName().toUpperCase(Locale.ROOT)), args);
            } else {
                dispatch(player, args);
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || government.jailed(player) || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (command.getName().equals("shopaverage")) return "hand".startsWith(prefix) ? List.of("hand") : List.of();
        if (command.getName().equals("election")) return "gui".startsWith(prefix) ? List.of("GUI") : List.of();
        if (command.getName().equals("request")) {
            return List.of("passport", "visa", "status").stream().filter(item -> item.startsWith(prefix)).toList();
        }
        if (command.getName().equals("company") || command.getName().equals("corporation")) {
            return List.of("help", "list", "create", "remove", "edit", "use", "invite", "join", "leave",
                "transfer", "plot", "train", "station", "machine", "settlement", "gate", "collect", "set", "loan").stream().filter(item -> item.startsWith(prefix)).toList();
        }
        return List.of("help", "market", "buy", "sell", "company", "crypto", "auction", "bank",
            "law", "role", "tax", "plot", "court", "jail", "nation", "town", "job", "constitution", "regulations", "political_party", "train_route", "ic", "ticket", "coupler", "borrow", "repayment").stream().filter(item -> item.startsWith(prefix)).toList();
    }

    void run(CommandSender sender, Runnable operation) {
        try {
            operation.run();
        } catch (State.Rejected error) {
            sender.sendMessage(error.getMessage());
        } catch (RuntimeException error) {
            getLogger().log(Level.SEVERE, "Operation failed for " + sender.getName(), error);
            sender.sendMessage("処理に失敗しました。管理者に連絡してください。");
        }
    }

    private void dispatch(Player player, String[] args) {
        if (args.length == 0 || action(args, 0).equals("help")) {
            help(player, args.length > 1 ? action(args, 1) : "");
            return;
        }
        switch (action(args, 0)) {
            case "market" -> menus.open(player, Menus.Screen.MARKET);
            case "buy", "sell" -> market.trade(player, key(value(args, 1)), number(value(args, 2), 1, 1_000_000),
                action(args, 0).equals("buy"));
            case "crypto" -> {
                require(action(args, 1).equals("create"), "使い方 /tel crypto create <銘柄> <初値>");
                market.mint(player, key(value(args, 2)), basePrice(args, 3));
                player.sendMessage("暗号資産を作成しました。作成手数料 100000.00");
            }
            case "company" -> company(player, args);
            case "train_route" -> enterpriseCommands.routeMap(player, value(args, 1));
            case "ic" -> enterpriseCommands.ic(player, args);
            case "ticket" -> enterpriseCommands.ticket(player, args);
            case "coupler" -> couplers.issue(player);
            case "borrow" -> enterpriseCommands.borrow(player, args);
            case "repayment" -> enterpriseCommands.repay(player, args);
            case "auction" -> auction(player, args);
            case "bank" -> bank(player, args);
            case "law" -> {
                if (args.length == 1 || action(args, 1).equals("gui")) civicCommands.gui(player, Civics.Document.LAW);
                else law(player, args);
            }
            case "nation" -> civicCommands.nation(player, args);
            case "town" -> civicCommands.town(player, args);
            case "job" -> civicCommands.job(player, args);
            case "constitution" -> civicCommands.gui(player, Civics.Document.CONSTITUTION);
            case "regulations" -> civicCommands.gui(player, Civics.Document.REGULATION);
            case "political_party", "potilical_party" -> civicCommands.party(player, args);
            case "role" -> role(player, args);
            case "tax" -> tax(player, args);
            case "plot" -> civicCommands.plot(player, args);
            case "court" -> {
                require(player.teleport(government.court(player)), "法廷へ移動できませんでした。");
                player.sendMessage("自国の法廷へ移動しました。");
            }
            case "jail" -> jail(player, args);
            default -> throw new State.Rejected("不明なコマンドです。 /tel help");
        }
    }

    private void company(Player player, String[] args) {
        String action = action(args, 1);
        if (action.equals("list")) {
            show(player, market.companies());
            return;
        }
        String id = key(value(args, 2));
        switch (action) {
            case "create" -> market.createCompany(player, id, text(args, 3, 80));
            case "info" -> {
                player.sendMessage(market.company(id));
                return;
            }
            case "invite" -> market.invite(player, id, knownPlayer(value(args, 3)).getUniqueId());
            case "join" -> market.join(player, id);
            case "leave" -> market.leave(player, id);
            case "transfer" -> market.transfer(player, id, knownPlayer(value(args, 3)).getUniqueId());
            case "ipo" -> market.list(player, id, key(value(args, 3)), basePrice(args, 4));
            default -> throw new State.Rejected("使い方 /tel help company");
        }
        player.sendMessage("組織情報を更新しました。 " + id);
    }

    private void auction(Player player, String[] args) {
        if (args.length == 1 || action(args, 1).equals("gui")) {
            menus.open(player, Menus.Screen.AUCTION);
            return;
        }
        switch (action(args, 1)) {
            case "list" -> auctions.list(player, cash(args, 2), number(value(args, 3), 1, 10_080));
            case "bid" -> auctions.bid(player, key(value(args, 2)), cash(args, 3));
            case "cancel" -> auctions.cancel(player, key(value(args, 2)));
            case "claims" -> show(player, auctions.claims(player.getUniqueId()));
            case "claim" -> auctions.claim(player, key(value(args, 2)));
            default -> throw new State.Rejected("使い方 /tel help auction");
        }
    }

    private void bank(Player player, String[] args) {
        String action = action(args, 1);
        if (action.equals("list")) {
            show(player, banks.ids());
            return;
        }
        String id = key(value(args, 2));
        switch (action) {
            case "create" -> {
                banks.create(player, id, text(args, 3, 80));
                player.sendMessage("銀行を開設しました。 " + id);
            }
            case "info" -> banks.info(player, id);
            case "deposit", "withdraw" -> banks.move(player, id, cash(args, 3), action.equals("deposit"));
            case "fee" -> {
                banks.fee(player, id, cents(value(args, 3), 0, 1_000));
                player.sendMessage("入金手数料を設定しました。");
            }
            case "collect" -> banks.collect(player, id);
            default -> throw new State.Rejected("使い方 /tel help bank");
        }
    }

    private void law(Player player, String[] args) {
        switch (action(args, 1)) {
            case "list" -> {
                List<Civics.Entry> laws = civics.documents(player, Civics.Document.LAW);
                if (laws.isEmpty()) player.sendMessage("登録された法律がありません。");
                for (Civics.Entry law : laws) player.sendMessage(law.id() + " / " + law.title());
            }
            case "show" -> {
                String selector = text(args, 2, 80);
                Civics.Entry law = civics.documents(player, Civics.Document.LAW).stream()
                    .filter(entry -> entry.id().equalsIgnoreCase(selector) || entry.title().equalsIgnoreCase(selector))
                    .findFirst().orElseThrow(() -> new State.Rejected("指定の法律が見つかりません。"));
                player.sendMessage(law.title() + " / " + law.body());
                if (!law.penalty().isEmpty()) player.sendMessage("罰則 " + law.penalty());
                if (!law.note().isEmpty()) player.sendMessage("追記 " + law.note());
            }
            case "set", "remove" -> {
                boolean set = action(args, 1).equals("set");
                civics.changeLegacyLaw(player, key(value(args, 2)), set ? text(args, 3, 400) : null);

            }
            default -> throw new State.Rejected("使い方 /tel help law");
        }
    }

    private void role(Player player, String[] args) {
        String action = action(args, 1);
        if (action.equals("mine")) {
            show(player, government.roles(player));
            return;
        }
        require(action.equals("grant") || action.equals("revoke"), "使い方 /tel help law");
        government.grant(player, knownPlayer(value(args, 2)).getUniqueId(),
            parse(Government.Role.class, value(args, 3)), action.equals("grant"));
        player.sendMessage("国内の役職を更新しました。");
    }

    private void tax(Player player, String[] args) {
        String action = args.length == 1 ? "show" : action(args, 1);
        switch (action) {
            case "show" -> player.sendMessage(government.tax(player));
            case "set" -> {
                long rate = cents(value(args, 2), 0, 10_000);
                government.tax(player, rate);
                player.sendMessage("証券税を " + money(rate) + "% に設定しました。");
            }
            case "collect" -> government.collect(player);
            default -> throw new State.Rejected("使い方 /tel tax [show|set <割合>|collect]");
        }
    }

    private void jail(Player player, String[] args) {
        String action = action(args, 1);
        switch (action) {
            case "arrest" -> {
                Player target = Bukkit.getPlayerExact(value(args, 2));
                require(target != null, "オンラインの対象プレイヤーが見つかりません。");
                String lawId = value(args, 4).toLowerCase(Locale.ROOT);
                require(lawId.matches("[a-z0-9_-]{2,36}"), "法令 ID が無効です。");
                government.jail(player, target, number(value(args, 3), 1, 10_080), lawId);
                target.closeInventory();
                jail.sendToJail(target);
                player.sendMessage(target.getName() + " を拘禁しました。");
            }
            case "release" -> {
                OfflinePlayer target = knownPlayer(value(args, 2));
                government.release(player, target);
                player.sendMessage("拘禁を解除しました。");
            }
            default -> throw new State.Rejected("使い方 /tel help law");
        }
    }

    private void help(Player player, String topic) {
        String[] lines = switch (topic) {
            case "company" -> new String[]{
                "/tel company create <ID> <名前>",
                "/tel company list | info <ID>",
                "/tel company invite <ID> <プレイヤー> | join <ID> | leave <ID>",
                "/tel company transfer <ID> <メンバー>",
                "/tel company ipo <ID> <銘柄> <初値>"};
            case "auction" -> new String[]{
                "/tel auction — GUI",
                "/tel auction list <開始価格> <分> — メインハンドを出品",
                "/tel auction bid <ID> <金額> | cancel <ID>",
                "/tel auction claims | claim <ID>"};
            case "bank" -> new String[]{
                "/tel bank create <ID> <名前> | list | info <ID>",
                "/tel bank deposit <ID> <金額> | withdraw <ID> <金額>",
                "/tel bank fee <ID> <割合> | collect <ID>"};
            case "train" -> new String[]{
                "/tel train_route <企業コード> / /tel coupler",
                "/tel ic balance / /tel ic charge <金額> / /tel ticket use",
                "/company gate set [both|entry|exit] | remove",
                "/company settlement <set|remove>",
                "/company station rail set [秒] | remove",
                "法人は /corporation を使います。"};
            case "civics" -> new String[]{
                "/tel nation set <MDSA|EA> <制度>",
                "/election GUI / /tel nation election <start|list|show|stand|vote>",
                "/tel nation <impeachment|no_confidence> start <後任のMCID> [理由]",
                "/request <passport|visa|status> / /tel nation <passport|visa|fta|tariffs|border>",
                "/tel nation set PFPP <金額> / /tel political_party <bank|deposit|withdrawal|history|grant>",
                "/tel nation constitution|law <add|remove> / /tel town regulations <add|edit|remove>",
                "/tel constitution GUI / /tel law GUI / /tel regulations GUI",
                "/tel job set <MCID> <judge|prosecutor|policeman|politician>",
                "/tel plot <set|show|puppet|decentralization> / /tel nation puppet <join|rebel|release|status>",
                "/tel political_party <create|remove|add|join|leave|GUI>"};
            case "law" -> new String[]{
                "/tel law list | show <ID> | set <ID> <本文> | remove <ID>",
                "/tel role mine | grant <プレイヤー> <役職> | revoke <プレイヤー> <役職>",
                "役職 minister / legislator / judge / warden / treasurer",
                "/tel tax show | set <割合> | collect",
                "/tel plot set <jail|courthouse|police|legislature> | /tel court",
                "/tel jail arrest <プレイヤー> <分> <法律ID> | release <プレイヤー>"};
            default -> new String[]{
                "/shopavg [hand] — ショップ価格 GUI / 手持ちの平均単価",
                "/tel market — 証券市場 GUI",
                "/tel buy <銘柄> <数量> | /tel sell <銘柄> <数量>",
                "/tel crypto create <銘柄> <初値> — 手数料 100000",
                "/tel borrow <企業コード> <金額> | info / /tel repayment <企業コード> <金額>",
                "/tel help <company|auction|bank|law|civics|train>"};
        };
        player.sendMessage(lines);
    }

    private void show(Player player, Collection<String> values) {
        if (values.isEmpty()) {
            player.sendMessage("該当する情報がありません。");
            return;
        }
        List<String> sorted = values.stream().sorted().toList();
        for (int start = 0; start < sorted.size(); start += 8) {
            player.sendMessage(String.join(", ", sorted.subList(start, Math.min(start + 8, sorted.size()))));
        }
    }

    private String action(String[] args, int index) {
        return value(args, index).toLowerCase(Locale.ROOT);
    }

    private long basePrice(String[] args, int index) {
        return cents(value(args, index), 1, 1_000_000);
    }

    private long cash(String[] args, int index) {
        return cents(value(args, index), 1, Input.MAX_CASH);
    }

    private <T extends Enum<T>> T parse(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new State.Rejected("使い方 /tel help law");
        }
    }
}
