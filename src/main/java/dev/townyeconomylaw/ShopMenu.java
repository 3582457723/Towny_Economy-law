package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class ShopMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int BACK = 45;
    private static final int PREVIOUS = 47;
    private static final int INFO = 49;
    private static final int NEXT = 51;
    private static final int REFRESH = 53;

    private enum Category {
        ALL("すべて", Material.CHEST),
        BLOCKS("ブロック", Material.BRICKS),
        FOOD("食べ物", Material.BREAD),
        EQUIPMENT("装備・道具", Material.IRON_PICKAXE),
        OTHER("その他", Material.PAPER);

        final String label;
        final Material icon;

        Category(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }

        static Category of(Material type) {
            if (type.isEdible()) return FOOD;
            String name = type.name();
            if (name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE")
                    || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_HELMET")
                    || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) {
                return EQUIPMENT;
            }
            return switch (type) {
                case BOW, CROSSBOW, TRIDENT, MACE, SHIELD, ELYTRA, FISHING_ROD, SHEARS,
                     FLINT_AND_STEEL, BRUSH, SPYGLASS, CARROT_ON_A_STICK, WARPED_FUNGUS_ON_A_STICK -> EQUIPMENT;
                default -> name.endsWith("_SPEAR") ? EQUIPMENT : type.isBlock() ? BLOCKS : OTHER;
            };
        }
    }

    private final TownyEconomyLaw plugin;
    private final Integrations api;
    private final Government government;

    ShopMenu(TownyEconomyLaw plugin, Integrations api, Government government) {
        this.plugin = plugin;
        this.api = api;
        this.government = government;
    }

    void open(Player player) {
        View view = new View(player, api.shopItems(player, null));
        render(view);
        player.openInventory(view.inventory);
    }

    private void render(View view) {
        Inventory inventory = view.inventory;
        inventory.clear();
        inventory.setItem(REFRESH, button(Material.CLOCK, "価格を更新", "現在のショップ価格を再取得"));
        if (view.category == null) {
            Category[] categories = Category.values();
            for (int index = 0; index < categories.length; index++) {
                Category category = categories[index];
                inventory.setItem(index, button(category.icon, category.label, matches(view.items, category).size() + " 種類"));
            }
            inventory.setItem(INFO, button(Material.COMPASS, view.worldName, "カテゴリを選んでアイテムを表示"));
            return;
        }

        List<Integrations.ShopItem> items = matches(view.items, view.category);
        int lastPage = Math.max(0, (items.size() - 1) / PAGE_SIZE);
        view.page = Math.min(view.page, lastPage);
        int first = view.page * PAGE_SIZE;
        for (int index = first; index < Math.min(first + PAGE_SIZE, items.size()); index++) {
            Integrations.ShopItem item = items.get(index);
            inventory.setItem(index - first, withPrices(item));
        }
        if (items.isEmpty()) inventory.setItem(22, button(Material.BARRIER, "該当するショップがありません", ""));
        inventory.setItem(BACK, button(Material.ARROW, "カテゴリへ戻る", ""));
        if (view.page > 0) inventory.setItem(PREVIOUS, button(Material.ARROW, "前のページ", ""));
        if (view.page < lastPage) inventory.setItem(NEXT, button(Material.ARROW, "次のページ", ""));
        inventory.setItem(INFO, button(view.category.icon, view.category.label,
            view.worldName + " / " + (view.page + 1) + " / " + (lastPage + 1) + " ページ"));
    }

    private List<Integrations.ShopItem> matches(List<Integrations.ShopItem> items, Category category) {
        if (category == Category.ALL) return items;
        return items.stream().filter(item -> Category.of(item.icon().getType()) == category).toList();
    }

    private ItemStack withPrices(Integrations.ShopItem item) {
        ItemStack icon = item.icon().clone();
        ItemMeta meta = icon.getItemMeta();
        List<Component> original = meta.lore();
        List<Component> lore = new ArrayList<>();
        item.prices().forEach(price -> lore.add(line(price.display())));
        if (original != null && !original.isEmpty()) {
            lore.add(Component.empty());
            lore.addAll(original);
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack button(Material material, String title, String description) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(line(title));
        if (!description.isEmpty()) meta.lore(List.of(line(description)));
        icon.setItemMeta(meta);
        return icon;
    }

    private Component line(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled || !(event.getWhoClicked() instanceof Player player) || government.jailed(player)
                || !view.world.equals(player.getWorld().getUID()) || event.getClick() != ClickType.LEFT) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= view.inventory.getSize() || view.inventory.getItem(slot) == null) return;
        plugin.run(player, () -> {
            if (slot == REFRESH) {
                view.items = api.shopItems(player, null);
            } else if (view.category == null && slot < Category.values().length) {
                view.category = Category.values()[slot];
                view.page = 0;
            } else if (slot == BACK) {
                view.category = null;
            } else if (slot == PREVIOUS) {
                view.page--;
            } else if (slot == NEXT) {
                view.page++;
            } else {
                return;
            }
            render(view);
        });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }

    @EventHandler
    public void worldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof View) player.closeInventory();
    }

    private static final class View implements InventoryHolder {
        private final Inventory inventory;
        private final UUID world;
        private final String worldName;
        private List<Integrations.ShopItem> items;
        private Category category;
        private int page;

        private View(Player player, List<Integrations.ShopItem> items) {
            this.world = player.getWorld().getUID();
            this.worldName = player.getWorld().getName();
            this.items = items;
            this.inventory = Bukkit.createInventory(this, 54, Component.text("ショップ平均価格"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
