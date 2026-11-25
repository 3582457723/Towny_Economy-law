package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Menus implements Listener {
    enum Screen { MARKET, AUCTION }
    private record Entry(String id, String quote, long bid, Diplomacy.Tariff tariff) {}

    private static final int PAGE_SIZE = 45;
    private final TownyEconomyLaw plugin;
    private final Market market;
    private final Auctions auctions;
    private final Government government;
    private final long refreshNanos;
    private long nextRefresh;

    Menus(TownyEconomyLaw plugin, Market market, Auctions auctions, Government government, long refreshMillis) {
        this.plugin = plugin;
        this.market = market;
        this.auctions = auctions;
        this.government = government;
        this.refreshNanos = TimeUnit.MILLISECONDS.toNanos(refreshMillis);
        this.nextRefresh = System.nanoTime() + refreshNanos;
    }

    void open(Player player, Screen screen) {
        Menu menu = new Menu(screen);
        render(player, menu);
        player.openInventory(menu.inventory);
    }

    void tick() {
        long now = System.nanoTime();
        if (now - nextRefresh < 0) return;
        long intervals = (now - nextRefresh) / refreshNanos + 1;
        nextRefresh += intervals * refreshNanos;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Menu menu) {
                plugin.run(player, () -> render(player, menu));
            }
        }
    }

    private void render(Player player, Menu menu) {
        List<String> ids = new ArrayList<>(menu.screen == Screen.MARKET ? market.tickers() : auctions.ids());
        ids.sort(String::compareTo);
        int lastPage = Math.max(0, (ids.size() - 1) / PAGE_SIZE);
        menu.page = Math.min(menu.page, lastPage);
        int start = menu.page * PAGE_SIZE;
        menu.entries.clear();

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            if (start + slot >= ids.size()) {
                put(menu, slot, null);
                continue;
            }
            String id = ids.get(start + slot);
            if (menu.screen == Screen.MARKET) {
                String quote = market.quote(id);
                menu.entries.put(slot, new Entry(id, quote, 0, null));
                put(menu, slot, named(new ItemStack(Material.PAPER), id, List.of(line(market.description(id)), line(quote),
                    line("保有 " + market.holding(player.getUniqueId(), id)), line("左クリックで1口購入 / 右クリックで1口売却"))));
            } else {
                Auctions.Lot lot = auctions.lot(id);
                Diplomacy.Tariff tariff = auctions.tariff(player, lot, lot.nextBid());
                menu.entries.put(slot, new Entry(id, null, lot.nextBid(), tariff));
                ItemStack icon = lot.item().clone();
                ItemMeta meta = icon.getItemMeta();
                List<Component> originalLore = meta == null ? null : meta.lore();
                List<Component> lore = originalLore == null ? new ArrayList<>() : new ArrayList<>(originalLore);
                long seconds = Math.max(0, (lot.end() - System.currentTimeMillis() + 999) / 1_000);
                lore.add(line(id + " / " + (lot.bidder() == null ? "開始価格 " : "現在価格 ") + money(lot.price())));
                lore.add(line("残り " + seconds + " 秒"));
                lore.add(line("左クリックで " + money(lot.nextBid()) + " を入札"));
                if (tariff.cents() != 0) lore.add(line("関税 " + money(tariff.cents()) + " / 合計 " + money(lot.nextBid() + tariff.cents())));
                put(menu, slot, named(icon, null, lore));
            }
        }
        put(menu, 45, menu.page > 0 ? named(new ItemStack(Material.ARROW), "前のページ", List.of()) : null);
        put(menu, 49, named(new ItemStack(Material.BOOK), (menu.page + 1) + " / " + (lastPage + 1), List.of()));
        put(menu, 53, menu.page < lastPage ? named(new ItemStack(Material.ARROW), "次のページ", List.of()) : null);
    }

    private void put(Menu menu, int slot, ItemStack item) {
        if (!Objects.equals(menu.inventory.getItem(slot), item)) menu.inventory.setItem(slot, item);
    }

    private ItemStack named(ItemStack icon, String name, List<Component> lore) {
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        if (name != null) meta.displayName(line(name));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private static Component line(String value) {
        return Component.text(value).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || government.jailed(player) || event.isShiftClick()) return;
        int slot = event.getRawSlot();
        ClickType click = event.getClick();
        if (slot < 0 || slot >= menu.inventory.getSize() || (click != ClickType.LEFT && click != ClickType.RIGHT)) return;
        plugin.run(player, () -> {
            if (menu.inventory.getItem(slot) == null) return;
            if (slot == 45) menu.page--;
            else if (slot == 53) menu.page++;
            else {
                Entry entry = menu.entries.get(slot);
                if (entry == null) return;
                if (menu.screen == Screen.MARKET) {
                    require(entry.quote().equals(market.quote(entry.id())), "価格が更新されました。確認して操作してください。");
                    market.trade(player, entry.id(), 1, click == ClickType.LEFT);
                } else if (click == ClickType.LEFT) {
                    auctions.bid(player, entry.id(), entry.bid(), entry.tariff());
                }
            }
            render(player, menu);
        });
    }

    private static final class Menu implements InventoryHolder {
        private final Inventory inventory;
        private final Screen screen;
        private final Map<Integer, Entry> entries = new HashMap<>();
        private int page;

        private Menu(Screen screen) {
            this.screen = screen;
            this.inventory = Bukkit.createInventory(this, 54, Component.text(screen == Screen.MARKET ? "証券市場" : "アイテムオークション"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
