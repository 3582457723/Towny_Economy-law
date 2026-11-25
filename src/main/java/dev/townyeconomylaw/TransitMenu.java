package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class TransitMenu implements Listener {
    private record Row(Material material, String title, String detail, Runnable action) {}
    private static final int PAGE = 45;
    private final TownyEconomyLaw plugin;
    private final Government government;
    private final Transit transit;
    private final Set<UUID> pendingOpen = new HashSet<>();

    TransitMenu(TownyEconomyLaw plugin, Government government, Transit transit) {
        this.plugin = plugin;
        this.government = government;
        this.transit = transit;
    }

    void routeMap(Player player, Enterprises.Firm firm) {
        List<Row> rows = transit.routes(firm).stream().map(route -> new Row(Material.MAP, route.name(),
            route.stops().size() + " 駅", () -> route(player, firm, route))).toList();
        open(player, firm.code() + " 路線図", rows, null);
    }

    private void route(Player player, Enterprises.Firm firm, Transit.Route route) {
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < route.stops().size(); index++) {
            String stop = route.stops().get(index);
            String next = index + 1 < route.stops().size() ? "↓ " + route.stops().get(index + 1) : "終点";
            rows.add(new Row(Material.RAIL, (index + 1) + "  ●  " + stop, next, null));
        }
        open(player, route.name(), rows, () -> routeMap(player, firm));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || block.getType() != Material.DROPPER) return;
        Player player = event.getPlayer();
        plugin.run(player, () -> {
            Transit.Machine machine = transit.machine(block);
            if (machine == null) return;
            event.setCancelled(true);
            if (event.getHand() != EquipmentSlot.HAND || !canUse(player) || !pendingOpen.add(player.getUniqueId())) return;
            // Let the interaction finish before opening an inventory for the player.
            Bukkit.getScheduler().runTask(plugin, () -> {
                pendingOpen.remove(player.getUniqueId());
                if (canUse(player)) plugin.run(player, checked(player, block, machine, live -> machine(player, block, live)));
            });
        });
    }

    private boolean canUse(Player player) {
        return player.isOnline() && !government.jailed(player) && player.hasPermission("townyeconomylaw.use");
    }

    private void machine(Player player, Block block, Transit.Machine machine) {
        List<Row> rows = new ArrayList<>();
        if (machine.type() == Transit.MachineType.TICKET) {
            rows.add(new Row(Material.PAPER, "切符を購入", "乗車駅と降車駅を選択",
                checked(player, block, machine, live -> origins(player, block, live))));
            rows.add(new Row(Material.NAME_TAG, "ICカード発行・再発行",
                money(Transit.IC_ISSUE_COST) + " / チャージ " + money(Transit.IC_ISSUE_CREDIT) + " 付き",
                checked(player, block, machine, live -> issueCard(player, block, live))));
        } else {
            rows.add(new Row(Material.PAPER, "切符ののりこし精算", "切符をメインハンドに持って選択",
                checked(player, block, machine, live -> settlement(player, block, live))));
        }
        if (transit.holdsChargeCard(player)) {
            rows.add(new Row(Material.EMERALD, "100.00 チャージ", "Vault 残高からチャージ", checked(player, block, machine, live -> transit.charge(player, 10_000))));
            rows.add(new Row(Material.EMERALD_BLOCK, "1000.00 チャージ", "Vault 残高からチャージ", checked(player, block, machine, live -> transit.charge(player, 100_000))));
            rows.add(new Row(Material.BOOK, "IC残高確認", "現在の残高を表示", checked(player, block, machine, live -> transit.balance(player))));
        } else {
            rows.add(new Row(Material.NAME_TAG, "ICカードのチャージ", "自分のICカードをオフハンドに持って開き直してください", null));
        }
        open(player, machine.firm().code() + (machine.type() == Transit.MachineType.SETTLEMENT ? " のりこし精算機" : " 券売機"), rows, null);
    }

    private void issueCard(Player player, Block block, Transit.Machine machine) {
        Row confirm = new Row(Material.NAME_TAG, money(Transit.IC_ISSUE_COST) + " を支払って発行",
            "残高に " + money(Transit.IC_ISSUE_CREDIT) + " を加算 / 再発行で旧カードは無効",
            checked(player, block, machine, live -> {
                transit.card(player);
                machine(player, block, live);
            }));
        open(player, "ICカードの発行確認", List.of(confirm),
            checked(player, block, machine, live -> machine(player, block, live)));
    }

    private void settlement(Player player, Block block, Transit.Machine machine) {
        Transit.Settlement quote = transit.settlement(player, machine);
        Row confirm = new Row(Material.EMERALD, "追加運賃を支払う", quote.from() + " → " + quote.to()
            + " / " + money(quote.difference()), checked(player, block, machine, live -> {
                transit.settle(player, live, quote);
                machine(player, block, live);
            }));
        open(player, "のりこし精算 " + machine.station(), List.of(confirm),
            checked(player, block, machine, live -> machine(player, block, live)));
    }

    private void origins(Player player, Block block, Transit.Machine machine) {
        List<String> origins = transit.origins(machine);
        if (origins.size() == 1) {
            destinations(player, block, machine, origins.getFirst());
            return;
        }
        List<Row> rows = origins.stream().map(origin -> new Row(Material.RAIL, origin, "ここから乗車",
            checked(player, block, machine, live -> destinations(player, block, live, origin)))).toList();
        open(player, "乗車駅を選択", rows, checked(player, block, machine, live -> machine(player, block, live)));
    }

    private void destinations(Player player, Block block, Transit.Machine machine, String from) {
        List<Row> rows = transit.destinations(machine, from).stream().map(fare -> new Row(Material.RAIL, fare.to(),
            "運賃 " + money(fare.cents()), checked(player, block, machine, live -> payment(player, block, live, fare)))).toList();
        open(player, from + " → 降車駅を選択", rows, checked(player, block, machine, live -> {
            if (transit.origins(live).size() <= 1) machine(player, block, live);
            else origins(player, block, live);
        }));
    }

    private void payment(Player player, Block block, Transit.Machine machine, Transit.Fare fare) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Material.EMERALD, "Vault で支払う", money(fare.cents()),
            checked(player, block, machine, live -> purchase(player, block, live, fare, false))));
        if (transit.holdsCard(player)) {
            rows.add(new Row(Material.NAME_TAG, "ICカードで支払う", money(fare.cents()),
                checked(player, block, machine, live -> purchase(player, block, live, fare, true))));
        }
        open(player, fare.from() + " → " + fare.to(), rows,
            checked(player, block, machine, live -> destinations(player, block, live, fare.from())));
    }

    private void purchase(Player player, Block block, Transit.Machine machine, Transit.Fare fare, boolean card) {
        transit.buy(player, machine, fare, card);
        machine(player, block, machine);
    }

    private Runnable checked(Player player, Block block, Transit.Machine snapshot, Consumer<Transit.Machine> operation) {
        return () -> {
            require(player.getWorld().equals(block.getWorld()) && player.getLocation().distanceSquared(block.getLocation().clone().add(.5, .5, .5)) <= 64,
                "機器の近くで操作してください。");
            Transit.Machine current = transit.machine(block);
            require(current != null && current.key().equals(snapshot.key()) && current.firm().id().equals(snapshot.firm().id())
                && current.station().equals(snapshot.station()) && current.type() == snapshot.type(), "機器の情報が変更されました。");
            operation.accept(current);
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        transit.destroyMachines(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) {
        transit.destroyMachines(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) {
        transit.destroyMachines(event.blockList());
    }

    private void open(Player player, String title, List<Row> rows, Runnable back) {
        View view = new View(title, rows, back);
        render(view);
        player.openInventory(view.inventory);
    }

    private void render(View view) {
        view.inventory.clear();
        int first = view.page * PAGE;
        int end = Math.min(first + PAGE, view.rows.size());
        for (int index = first; index < end; index++) view.inventory.setItem(index - first, icon(view.rows.get(index)));
        if (view.rows.isEmpty()) view.inventory.setItem(22, icon(new Row(Material.BARRIER, "登録情報がありません", "", null)));
        if (view.page > 0) view.inventory.setItem(45, icon(new Row(Material.ARROW, "前のページ", "", null)));
        if (view.back != null) view.inventory.setItem(49, icon(new Row(Material.OAK_DOOR, "戻る", "", null)));
        if (end < view.rows.size()) view.inventory.setItem(53, icon(new Row(Material.ARROW, "次のページ", "", null)));
    }

    private ItemStack icon(Row row) {
        ItemStack item = new ItemStack(row.material());
        var meta = item.getItemMeta();
        meta.displayName(line(row.title()));
        if (!row.detail().isEmpty()) meta.lore(List.of(line(row.detail())));
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || !(top.getHolder() instanceof View view)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || event.getClick() != ClickType.LEFT || !(event.getWhoClicked() instanceof Player player)
                || !canUse(player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize() || top.getItem(slot) == null) return;
        if (slot == 45 || slot == 53) {
            view.page += slot == 45 ? -1 : 1;
            render(view);
            return;
        }
        int index = view.page * PAGE + slot;
        Runnable action = slot == 49 ? view.back : slot < PAGE && index < view.rows.size() ? view.rows.get(index).action() : null;
        if (action == null || view.pending) return;
        view.pending = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            view.pending = false;
            if (player.getOpenInventory().getTopInventory() == top && canUse(player)) plugin.run(player, action);
        });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof View) event.setCancelled(true);
    }

    private static final class View implements InventoryHolder {
        private final Inventory inventory;
        private final List<Row> rows;
        private final Runnable back;
        private int page;
        private boolean pending;

        private View(String title, List<Row> rows, Runnable back) {
            this.rows = rows;
            this.back = back;
            this.inventory = Bukkit.createInventory(this, 54, Component.text(title));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
