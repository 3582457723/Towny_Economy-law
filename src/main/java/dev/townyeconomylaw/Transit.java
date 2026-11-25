package dev.townyeconomylaw;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import static dev.townyeconomylaw.Enterprises.path;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Transit {
    static final long IC_ISSUE_COST = 100_000L;
    static final long IC_ISSUE_CREDIT = 50_000L;

    record Route(String name, List<String> stops) {}
    record Fare(String from, String to, long cents) {}
    enum MachineType { TICKET, SETTLEMENT }
    record Machine(String key, Enterprises.Firm firm, String station, MachineType type) {}
    record Settlement(String ticket, String from, String booked, String to, long paid, long difference) {}

    private final State state;
    private final Integrations api;
    private final Enterprises firms;
    private final EnterpriseLots lots;
    private final NamespacedKey cardKey;
    private final NamespacedKey ticketKey;
    private final NamespacedKey cardOwnerKey;
    private final NamespacedKey cardBalanceKey;

    Transit(TownyEconomyLaw plugin, State state, Integrations api, Enterprises firms, EnterpriseLots lots) {
        this.state = state;
        this.api = api;
        this.firms = firms;
        this.lots = lots;
        cardKey = new NamespacedKey(plugin, "transit_card");
        ticketKey = new NamespacedKey(plugin, "transit_ticket");
        cardOwnerKey = new NamespacedKey(plugin, "transit_card_owner");
        cardBalanceKey = new NamespacedKey(plugin, "transit_card_balance_cents");
    }

    private YamlConfiguration data() {
        return state.data();
    }

    private static String key(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private String routesPath(Enterprises.Firm firm) {
        return path(firm) + ".routes";
    }

    List<Route> routes(Enterprises.Firm firm) {
        String root = routesPath(firm);
        return state.keys(root).stream().map(id -> new Route(data().getString(root + "." + id + ".name"),
            data().getStringList(root + "." + id + ".stops"))).sorted(java.util.Comparator.comparing(Route::name)).toList();
    }

    void createRoute(Player player, Enterprises.Firm firm, String name) {
        firms.leader(player, firm);
        String route = routesPath(firm) + "." + key(name);
        require(!data().contains(route), "同名の路線があります。");
        state.transaction(undo -> data().set(route + ".name", name));
        player.sendMessage("路線を作成しました。 " + name);
    }

    void addStops(Player player, Enterprises.Firm firm, String routeName, List<String> names) {
        firms.leader(player, firm);
        String route = routesPath(firm) + "." + key(routeName);
        require(data().contains(route), "指定の路線が見つかりません。");
        List<String> stops = Stream.concat(data().getStringList(route + ".stops").stream(), names.stream()).toList();
        require(stops.stream().map(Transit::key).distinct().count() == stops.size(), "同一路線に同名駅は登録できません。");
        state.transaction(undo -> data().set(route + ".stops", stops));
        player.sendMessage("駅を追加しました。 " + String.join(" → ", names));
    }

    private String farePath(Enterprises.Firm firm, String from, String to) {
        return path(firm) + ".fares." + key(from) + "." + key(to);
    }

    void fare(Player player, Enterprises.Firm firm, String start, String end, long cents) {
        firms.leader(player, firm);
        require(!start.equalsIgnoreCase(end), "開始駅と終了駅を別にしてください。");
        String from = station(firm, start);
        String to = station(firm, end);
        require(routes(firm).stream().anyMatch(route -> hasStop(route, from) && hasStop(route, to)),
            "開始駅と終了駅を同じ路線に登録してください。");
        String entry = farePath(firm, from, to);
        state.transaction(undo -> {
            data().set(entry + ".from", from);
            data().set(entry + ".to", to);
            data().set(entry + ".cents", cents);
        });
        player.sendMessage(from + " → " + to + " / " + money(cents));
    }

    private boolean hasStop(Route route, String station) {
        return route.stops().stream().anyMatch(stop -> stop.equalsIgnoreCase(station));
    }

    private String station(Enterprises.Firm firm, String name) {
        return routes(firm).stream().flatMap(route -> route.stops().stream()).filter(stop -> stop.equalsIgnoreCase(name))
            .findFirst().orElseThrow(() -> new State.Rejected("指定の駅が路線にありません。 " + name));
    }

    List<String> origins(Machine machine) {
        String root = path(machine.firm()) + ".fares";
        return state.keys(root).stream().flatMap(start -> state.keys(root + "." + start).stream()
            .map(end -> data().getString(root + "." + start + "." + end + ".from")))
            .distinct().sorted().filter(from -> machine.station().isEmpty() || machine.station().equalsIgnoreCase(from)).toList();
    }

    List<Fare> destinations(Machine machine, String from) {
        return destinations(machine.firm(), from);
    }

    private List<Fare> destinations(Enterprises.Firm firm, String from) {
        String root = path(firm) + ".fares." + key(from);
        return state.keys(root).stream().map(id -> {
            String entry = root + "." + id;
            return new Fare(data().getString(entry + ".from"), data().getString(entry + ".to"), data().getLong(entry + ".cents"));
        }).sorted(java.util.Comparator.comparing(Fare::to)).toList();
    }

    private static String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private String machinePath(Block block) {
        return "transit-machines." + blockKey(block);
    }

    void configureMachine(Player player, Enterprises.Firm firm, Block block, boolean install) {
        configureMachine(player, firm, block, install, MachineType.TICKET);
    }

    void configureMachine(Player player, Enterprises.Firm firm, Block block, boolean install, MachineType type) {
        firms.leader(player, firm);
        require(block != null && block.getType() == Material.DROPPER, "5ブロック以内のドロッパーを見てください。");
        EnterpriseLots.Claim claim = lots.at(block.getLocation());
        require(claim != null && lots.accessible(claim, firm), "自社または共有されたロットで実行してください。");
        String entry = machinePath(block);
        String owner = data().getString(entry + ".firm");
        require(install ? owner == null || owner.equals(firm.id()) : firm.id().equals(owner), "この機器は別の組織が管理しています。");
        if (install && type == MachineType.SETTLEMENT) {
            require("station".equals(claim.lot().type()) && !claim.lot().station().isBlank(), "駅名が設定された駅ロットで実行してください。");
        }
        state.transaction(undo -> {
            if (install) {
                data().set(entry + ".firm", firm.id());
                data().set(entry + ".plot", claim.key());
                data().set(entry + ".type", type.name());
            } else {
                data().set(entry, null);
            }
        });
        player.sendMessage(install ? (type == MachineType.TICKET ? "汎用券売機を登録しました。" : "のりこし精算機を登録しました。") : "機器を解除しました。");
    }

    Machine machine(Block block) {
        String entry = machinePath(block);
        String id = data().getString(entry + ".firm");
        if (id == null || block.getType() != Material.DROPPER) return null;
        Enterprises.Firm firm = firms.get(id);
        EnterpriseLots.Claim claim = firm == null ? null : lots.at(block.getLocation());
        if (claim == null || !claim.key().equals(data().getString(entry + ".plot")) || !lots.accessible(claim, firm)) return null;
        String station = "station".equals(claim.lot().type()) ? claim.lot().station() : "";
        MachineType type = MachineType.valueOf(data().getString(entry + ".type", MachineType.TICKET.name()));
        if (type == MachineType.SETTLEMENT && station.isEmpty()) return null;
        return new Machine(blockKey(block), firm, station, type);
    }

    void destroyMachines(List<Block> blocks) {
        List<String> registered = blocks.stream().map(this::machinePath).filter(data()::contains).toList();
        if (!registered.isEmpty()) state.transaction(undo -> registered.forEach(entry -> data().set(entry, null)));
    }

    void buy(Player player, Machine machine, Fare fare, boolean card) {
        require(machine.type() == MachineType.TICKET, "切符の購入には券売機を使ってください。");
        String entry = farePath(machine.firm(), fare.from(), fare.to());
        require(data().contains(entry) && data().getLong(entry + ".cents") == fare.cents()
            && (machine.station().isEmpty() || machine.station().equalsIgnoreCase(fare.from())), "運賃が変更されました。券売機を開き直してください。");
        String debit = card ? activeCard(player, EquipmentSlot.HAND) + ".balance" : null;
        long balance = card ? data().getLong(debit) : 0;
        require(!card || balance >= fare.cents(), "ICカードの残高が不足しています。");
        String revenue = path(machine.firm()) + ".revenue";
        long income = Input.credit(data().getLong(revenue), fare.cents());
        String id = UUID.randomUUID().toString();
        String ticket = "transit-tickets." + id;
        ItemStack item = ticketItem(machine.firm(), id, fare.from(), fare.to());
        state.transaction(undo -> {
            give(player, item, undo);
            if (card) writeBalance(player, EquipmentSlot.HAND, debit, balance - fare.cents(), undo);
            else api.economy(undo, player.getUniqueId(), fare.cents(), false);
            data().set(revenue, income);
            data().set(ticket + ".owner", player.getUniqueId().toString());
            data().set(ticket + ".firm", machine.firm().id());
            data().set(ticket + ".from", fare.from());
            data().set(ticket + ".to", fare.to());
            data().set(ticket + ".paid", fare.cents());
        });
        player.sendMessage("切符を発行しました。 " + fare.from() + " → " + fare.to() + " / " + money(fare.cents()));
    }

    void card(Player player) {
        String root = "transit-cards." + player.getUniqueId();
        long balance = Input.credit(data().getLong(root + ".balance"), IC_ISSUE_CREDIT);
        String id = UUID.randomUUID().toString();
        ItemStack card = withCardProperties(player, marked(Material.NAME_TAG, "交通系ICカード", cardKey, id), balance);
        state.transaction(undo -> {
            give(player, card, undo);
            api.economy(undo, player.getUniqueId(), IC_ISSUE_COST, false);
            data().set(root + ".serial", id);
            data().set(root + ".owner", player.getUniqueId().toString());
            data().set(root + ".balance", balance);
        });
        player.sendMessage("ICカードを発行しました。支払い " + money(IC_ISSUE_COST) + " / 残高 " + money(balance));
    }

    private String activeCard(Player player, EquipmentSlot slot) {
        require(holdsCard(player, slot), "有効な自分のICカードを" + (slot == EquipmentSlot.OFF_HAND ? "オフハンド" : "メインハンド") + "に持ってください。");
        return "transit-cards." + player.getUniqueId();
    }

    boolean holdsCard(Player player) {
        return holdsCard(player, EquipmentSlot.HAND);
    }

    boolean holdsChargeCard(Player player) {
        return holdsCard(player, EquipmentSlot.OFF_HAND);
    }

    private boolean holdsCard(Player player, EquipmentSlot slot) {
        String serial = data().getString("transit-cards." + player.getUniqueId() + ".serial");
        return serial != null && serial.equals(marker(player.getInventory().getItem(slot), cardKey));
    }

    void charge(Player player, long amount) {
        String balance = activeCard(player, EquipmentSlot.OFF_HAND) + ".balance";
        long updated = Input.credit(data().getLong(balance), amount);
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, false);
            writeBalance(player, EquipmentSlot.OFF_HAND, balance, updated, undo);
        });
        player.sendMessage("ICカード残高 " + money(updated));
    }

    void balance(Player player) {
        EquipmentSlot slot = holdsChargeCard(player) ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        long amount = data().getLong(activeCard(player, slot) + ".balance");
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(slot, withCardProperties(player, inventory.getItem(slot).clone(), amount));
        player.sendMessage("ICカード所有者 " + player.getName() + " / 残高 " + money(amount));
    }

    private ItemStack withCardProperties(Player player, ItemStack card, long cents) {
        var meta = card.getItemMeta();
        meta.getPersistentDataContainer().set(cardOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        meta.getPersistentDataContainer().set(cardBalanceKey, PersistentDataType.LONG, cents);
        meta.lore(List.of(Component.text("所有者 " + player.getName()), Component.text("残高 " + money(cents)),
            Component.text("チャージはオフハンド / 改札はメインハンド")));
        card.setItemMeta(meta);
        return card;
    }

    private void writeBalance(Player player, EquipmentSlot slot, String balance, long cents, ArrayDeque<Runnable> undo) {
        data().set(balance, cents);
        ItemStack previous = player.getInventory().getItem(slot).clone();
        ItemStack updated = withCardProperties(player, previous.clone(), cents);
        player.getInventory().setItem(slot, updated);
        undo.push(() -> player.getInventory().setItem(slot, previous));
    }

    private void replaceHand(Player player, ItemStack previous, ItemStack replacement, ArrayDeque<Runnable> undo) {
        player.getInventory().setItemInMainHand(replacement);
        undo.push(() -> player.getInventory().setItemInMainHand(previous));
    }

    private ItemStack ticketItem(Enterprises.Firm firm, String id, String from, String to) {
        return marked(Material.PAPER, "鉄道切符", ticketKey, id,
            firm.name(), from + " → " + to, "乗車駅と降車駅の改札で使用");
    }

    private String activeTicket(Player player) {
        String id = marker(player, ticketKey);
        require(id != null && id.matches("[0-9a-f-]{36}"), "切符をメインハンドに持ってください。");
        String ticket = "transit-tickets." + id;
        require(player.getUniqueId().toString().equals(data().getString(ticket + ".owner")), "自分の有効な切符が必要です。");
        return ticket;
    }

    void useTicket(Player player) {
        String ticket = activeTicket(player);
        EnterpriseLots.Claim claim = lots.at(player.getLocation());
        Enterprises.Firm firm = firms.get(data().getString(ticket + ".firm"));
        require(firm != null && claim != null && lots.accessible(claim, firm) && "station".equals(claim.lot().type()),
            "対応する駅ロットで実行してください。");
        passTicket(player, firm, claim.lot().station(), !data().getBoolean(ticket + ".boarded"), ticket, undo -> {});
    }

    void gate(Player player, Enterprises.Firm firm, String station, boolean entry, State.Change passage) {
        if (marker(player, ticketKey) != null) {
            passTicket(player, firm, station, entry, activeTicket(player), passage);
        } else {
            require(holdsCard(player), "有効な切符または自分のICカードをメインハンドに持ってください。");
            passCard(player, firm, station, entry, passage);
        }
    }

    private void passTicket(Player player, Enterprises.Firm firm, String station, boolean entry, String ticket, State.Change passage) {
        require(firm.id().equals(data().getString(ticket + ".firm")), "この事業者の切符が必要です。");
        require(data().getBoolean(ticket + ".boarded") != entry,
            entry ? "この切符はすでに入場済みです。" : "先に乗車駅で入場してください。");
        String booked = data().getString(ticket + (entry ? ".from" : ".to"));
        require(station.equalsIgnoreCase(booked), entry ? "乗車駅は " + booked + " です。"
            : "降車駅は " + booked + " です。乗り越した場合は精算機をご利用ください。");
        state.transaction(undo -> {
            if (entry) {
                data().set(ticket + ".boarded", true);
            } else {
                ItemStack hand = player.getInventory().getItemInMainHand().clone();
                ItemStack remaining = hand.clone();
                remaining.setAmount(hand.getAmount() - 1);
                replaceHand(player, hand, remaining.getAmount() == 0 ? null : remaining, undo);
                data().set(ticket, null);
            }
            passage.apply(undo);
        });
        player.sendMessage(station + (entry ? " 駅で入場を記録しました。" : " 駅で切符を回収しました。"));
    }

    private void passCard(Player player, Enterprises.Firm firm, String station, boolean entry, State.Change passage) {
        String root = activeCard(player, EquipmentSlot.HAND);
        String trip = root + ".journey";
        String balance = root + ".balance";
        String journeyFirm = data().getString(trip + ".firm");
        long current = data().getLong(balance);
        if (entry) {
            require(journeyFirm == null, "未精算の入場記録があります。");
            long minimum = destinations(firm, station).stream().mapToLong(Fare::cents).min()
                .orElseThrow(() -> new State.Rejected("この駅からの運賃が設定されていません。"));
            require(current >= minimum, "入場に必要なIC残高が不足しています。");
            state.transaction(undo -> {
                data().set(trip + ".firm", firm.id());
                data().set(trip + ".from", station);
                writeBalance(player, EquipmentSlot.HAND, balance, current, undo);
                passage.apply(undo);
            });
            player.sendMessage(station + " 駅でICカードの入場を記録しました。");
            return;
        }
        require(firm.id().equals(journeyFirm), "この事業者のIC入場記録がありません。");
        String from = data().getString(trip + ".from");
        long fare = from.equalsIgnoreCase(station) ? 0 : configuredFare(firm, from, station);
        require(current >= fare, "ICカード残高が不足しています。精算機でチャージしてください。");
        state.transaction(undo -> {
            writeBalance(player, EquipmentSlot.HAND, balance, current - fare, undo);
            addRevenue(firm, fare);
            data().set(trip, null);
            passage.apply(undo);
        });
        player.sendMessage(station + " 駅で出場しました。運賃 " + money(fare) + " / IC残高 " + money(current - fare));
    }

    Settlement settlement(Player player, Machine machine) {
        require(machine.type() == MachineType.SETTLEMENT, "のりこし精算機をご利用ください。");
        String ticket = activeTicket(player);
        require(machine.firm().id().equals(data().getString(ticket + ".firm")), "この事業者の切符が必要です。");
        require(data().getBoolean(ticket + ".boarded"), "入場済みの切符が必要です。");
        String from = data().getString(ticket + ".from");
        String booked = data().getString(ticket + ".to");
        require(!machine.station().equalsIgnoreCase(booked), "この駅で精算せずに出場できます。");
        require(!machine.station().equalsIgnoreCase(from), "乗車駅ではのりこし精算できません。");
        long actual = configuredFare(machine.firm(), from, machine.station());
        long paid = data().contains(ticket + ".paid") ? data().getLong(ticket + ".paid") : configuredFare(machine.firm(), from, booked);
        return new Settlement(ticket, from, booked, machine.station(), paid, Math.max(0, actual - paid));
    }

    void settle(Player player, Machine machine, Settlement quote) {
        require(quote.equals(settlement(player, machine)), "精算内容が変更されました。画面を開き直してください。");
        String ticket = quote.ticket();
        String id = ticket.substring("transit-tickets.".length());
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), quote.difference(), false);
            addRevenue(machine.firm(), quote.difference());
            data().set(ticket + ".to", quote.to());
            data().set(ticket + ".paid", quote.paid() + quote.difference());
            ItemStack hand = player.getInventory().getItemInMainHand().clone();
            ItemStack replacement = ticketItem(machine.firm(), id, quote.from(), quote.to());
            replacement.setAmount(hand.getAmount());
            replaceHand(player, hand, replacement, undo);
        });
        player.sendMessage("精算しました。追加運賃 " + money(quote.difference()) + " / 降車駅 " + quote.to());
    }

    private long configuredFare(Enterprises.Firm firm, String from, String to) {
        String entry = farePath(firm, from, to);
        require(data().contains(entry), from + " → " + to + " の運賃が設定されていません。");
        return data().getLong(entry + ".cents");
    }

    private void addRevenue(Enterprises.Firm firm, long cents) {
        String path = path(firm) + ".revenue";
        data().set(path, Input.credit(data().getLong(path), cents));
    }

    private String marker(Player player, NamespacedKey type) {
        return marker(player.getInventory().getItemInMainHand(), type);
    }

    private String marker(ItemStack item, NamespacedKey type) {
        return item != null && item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer().get(type, PersistentDataType.STRING) : null;
    }

    private ItemStack marked(Material material, String name, NamespacedKey key, String id, String... description) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(Arrays.stream(description).map(Component::text).toList());
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private void give(Player player, ItemStack item, ArrayDeque<Runnable> undo) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] before = Arrays.stream(inventory.getStorageContents())
            .map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
        undo.push(() -> inventory.setStorageContents(before));
        require(inventory.addItem(item).isEmpty(), "インベントリに空きを作ってください。");
    }
}
