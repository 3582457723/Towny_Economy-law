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

final class CivicMenu implements Listener {
    record Row(Material material, String title, List<String> description) {}

    private static final int PAGE = 45;
    private final Government government;

    CivicMenu(Government government) {
        this.government = government;
    }

    void open(Player player, String title, List<Row> rows) {
        View view = new View(title, rows);
        render(view);
        player.openInventory(view.inventory);
    }

    private void render(View view) {
        Inventory inventory = view.inventory;
        inventory.clear();
        int first = view.page * PAGE;
        int end = Math.min(first + PAGE, view.rows.size());
        for (int index = first; index < end; index++) inventory.setItem(index - first, icon(view.rows.get(index)));
        if (view.rows.isEmpty()) inventory.setItem(22, icon(new Row(Material.BARRIER, "登録情報がありません", List.of())));
        if (view.page > 0) inventory.setItem(45, icon(new Row(Material.ARROW, "前のページ", List.of())));
        if (end < view.rows.size()) inventory.setItem(53, icon(new Row(Material.ARROW, "次のページ", List.of())));
    }

    private ItemStack icon(Row row) {
        ItemStack item = new ItemStack(row.material());
        var meta = item.getItemMeta();
        meta.displayName(line(row.title()));
        ArrayList<Component> lore = new ArrayList<>();
        for (String paragraph : row.description()) {
            int[] points = paragraph.codePoints().toArray();
            for (int first = 0; first < points.length; first += 30) {
                lore.add(line(new String(points, first, Math.min(30, points.length - first))));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || event.getClick() != ClickType.LEFT || !(event.getWhoClicked() instanceof Player player)
                || government.jailed(player)) return;
        int slot = event.getRawSlot();
        if ((slot != 45 && slot != 53) || view.inventory.getItem(slot) == null) return;
        view.page += slot == 45 ? -1 : 1;
        render(view);
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }

    private static final class View implements InventoryHolder {
        private final Inventory inventory;
        private final List<Row> rows;
        private int page;

        private View(String title, List<Row> rows) {
            this.rows = rows;
            this.inventory = Bukkit.createInventory(this, 54, Component.text(title));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
