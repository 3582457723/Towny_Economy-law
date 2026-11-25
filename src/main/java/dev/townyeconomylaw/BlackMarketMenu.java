package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.List;
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

import static dev.townyeconomylaw.Input.money;

final class BlackMarketMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45;
    private static final int NEXT = 53;
    private static final int CONFIRM = 31;
    private final TownyEconomyLaw plugin;
    private final BlackMarket market;
    private final Government government;

    BlackMarketMenu(TownyEconomyLaw plugin, BlackMarket market, Government government) {
        this.plugin = plugin;
        this.market = market;
        this.government = government;
    }

    void open(Player player) {
        Menu menu = new Menu();
        drawOffers(player, menu);
        player.openInventory(menu.inventory);
    }

    private void drawOffers(Player player, Menu menu) {
        menu.inventory.clear();
        menu.selected = null;
        List<BlackMarket.Offer> offers = market.offers(player);
        menu.page = Math.clamp(menu.page, 0, Math.max(0, (offers.size() - 1) / PAGE_SIZE));
        int start = menu.page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, offers.size());
        menu.visible = offers.subList(start, end);
        for (int i = 0; i < menu.visible.size(); i++) {
            BlackMarket.Offer offer = menu.visible.get(i);
            menu.inventory.setItem(i, offerIcon(offer));
        }
        if (menu.visible.isEmpty()) menu.inventory.setItem(22, button(Material.GRAY_STAINED_GLASS_PANE, "出品がありません"));
        if (menu.page > 0) menu.inventory.setItem(BACK, button(Material.ARROW, "前のページ"));
        if (end < offers.size()) menu.inventory.setItem(NEXT, button(Material.ARROW, "次のページ"));
        menu.inventory.setItem(49, button(Material.SUNFLOWER, "出品を更新"));
    }

    private void drawConfirmation(Menu menu, BlackMarket.Offer offer) {
        menu.inventory.clear();
        menu.selected = offer;
        menu.inventory.setItem(22, offerIcon(offer));
        menu.inventory.setItem(CONFIRM, button(Material.LIME_CONCRETE, "合計 " + money(offer.total()) + " で購入"));
        menu.inventory.setItem(BACK, button(Material.ARROW, "出品一覧に戻る"));
    }

    private ItemStack offerIcon(BlackMarket.Offer offer) {
        ItemStack icon = offer.item().clone();
        var meta = icon.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(label(offer.id() + " / " + (offer.restricted() ? "相手限定" : "匿名出品")));
        lore.add(label("価格 " + money(offer.price())));
        if (offer.tariff().cents() > 0) lore.add(label("関税 " + money(offer.tariff().cents()) + " / 合計 " + money(offer.total())));
        lore.add(label(offer.own() ? "あなたの出品" : "クリックして購入確認"));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack button(Material material, String text) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(label(text));
        item.setItemMeta(meta);
        return item;
    }

    private Component label(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || !(top.getHolder() instanceof Menu menu)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || event.getClick() != ClickType.LEFT || !(event.getWhoClicked() instanceof Player player)
            || government.jailed(player) || !player.hasPermission("townyeconomylaw.use")) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize() || top.getItem(slot) == null) return;
        plugin.run(player, () -> select(player, menu, slot));
    }

    private void select(Player player, Menu menu, int slot) {
        BlackMarket.Offer selected = menu.selected;
        if (selected != null) {
            if (slot == CONFIRM) {
                market.buy(player, selected.id(), selected);
                drawOffers(player, menu);
            } else if (slot == BACK) {
                drawOffers(player, menu);
            }
            return;
        }
        if (slot == BACK || slot == NEXT || slot == 49) {
            if (slot == BACK) menu.page--;
            if (slot == NEXT) menu.page++;
            drawOffers(player, menu);
        } else if (slot < menu.visible.size() && !menu.visible.get(slot).own()) {
            drawConfirmation(menu, menu.visible.get(slot));
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof Menu) event.setCancelled(true);
    }

    private static final class Menu implements InventoryHolder {
        private final Inventory inventory;
        private int page;
        private List<BlackMarket.Offer> visible = List.of();
        private BlackMarket.Offer selected;

        Menu() {
            inventory = Bukkit.createInventory(this, 54, Component.text("闇市"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
