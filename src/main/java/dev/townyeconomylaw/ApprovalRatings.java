package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import static dev.townyeconomylaw.State.require;

final class ApprovalRatings implements Listener {
    private static final int APPROVE = 10;
    private static final int RETRACT = 13;
    private static final int DISAPPROVE = 16;

    private record Totals(long approvals, long disapprovals, int citizens, String vote) {
        long responses() {
            return approvals + disapprovals;
        }

        String percentage() {
            return responses() == 0 ? "集計対象の票なし" : String.format(Locale.ROOT, "支持率 %.1f%%", 100.0 * approvals / responses());
        }
    }

    private final TownyEconomyLaw plugin;
    private final State state;
    private final Integrations api;
    private final Government government;

    ApprovalRatings(TownyEconomyLaw plugin, State state, Integrations api, Government government) {
        this.plugin = plugin;
        this.state = state;
        this.api = api;
        this.government = government;
    }

    void open(Player player) {
        Integrations.Nation nation = government.citizenship(player);
        require(nation.leader() != null, "国のリーダーが見つかりません。");
        View view = new View(nation, player.getUniqueId());
        render(view, nation);
        player.openInventory(view.inventory);
    }

    void refreshIncumbents() {
        List<String> expired = new ArrayList<>();
        for (String id : state.keys("nations")) {
            String path = "nations." + id + ".approval-rating";
            String countedLeader = state.data().getString(path + ".incumbent");
            if (countedLeader == null) continue;
            Integrations.Nation live = api.nationById(UUID.fromString(id));
            if (live == null || live.leader() == null || !countedLeader.equals(live.leader().toString())) expired.add(path);
        }
        if (!expired.isEmpty()) {
            state.transaction(undo -> expired.forEach(path -> state.data().set(path, null)));
        }
    }

    private String path(Integrations.Nation nation) {
        return Civics.nationPath(nation.id()) + ".approval-rating";
    }

    private boolean current(String path, UUID incumbent) {
        return incumbent.toString().equals(state.data().getString(path + ".incumbent"));
    }

    private Totals totals(Integrations.Nation nation, UUID voter) {
        List<UUID> citizens = api.citizens(nation).stream().distinct().toList();
        String path = path(nation);
        if (!current(path, nation.leader())) return new Totals(0, 0, citizens.size(), null);
        long approvals = 0;
        long disapprovals = 0;
        for (UUID resident : citizens) {
            String vote = state.data().getString(path + ".votes." + resident);
            if ("approve".equals(vote)) approvals++;
            if ("disapprove".equals(vote)) disapprovals++;
        }
        return new Totals(approvals, disapprovals, citizens.size(), state.data().getString(path + ".votes." + voter));
    }

    private void render(View view, Integrations.Nation nation) {
        Totals totals = totals(nation, view.owner);
        String leader = Bukkit.getOfflinePlayer(nation.leader()).getName();
        view.inventory.setItem(4, icon(Material.BOOK, nation.name() + "の支持率",
            List.of("現職 " + (leader == null ? nation.leader() : leader), totals.percentage(),
                "支持 " + totals.approvals() + "票 / 不支持 " + totals.disapprovals() + "票",
                "回答 " + totals.responses() + " / 住民 " + totals.citizens() + "人",
                "支持率の分母は支持・不支持の合計です")));
        view.inventory.setItem(APPROVE, icon(Material.LIME_DYE, "支持する", voteLore(totals.vote(), "approve")));
        view.inventory.setItem(RETRACT, icon(Material.GRAY_DYE, "投票を取り消す", List.of()));
        view.inventory.setItem(DISAPPROVE, icon(Material.RED_DYE, "支持しない", voteLore(totals.vote(), "disapprove")));
    }

    private List<String> voteLore(String vote, String choice) {
        return choice.equals(vote) ? List.of("現在の投票") : List.of();
    }

    private ItemStack icon(Material material, String title, List<String> details) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(line(title));
        meta.lore(details.stream().map(this::line).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (inventory == null || !(inventory.getHolder() instanceof View view)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || event.getClick() != ClickType.LEFT || !(event.getWhoClicked() instanceof Player player)
            || !view.owner.equals(player.getUniqueId()) || government.jailed(player) || !player.hasPermission("townyeconomylaw.use")) return;
        int slot = event.getRawSlot();
        if (slot == APPROVE || slot == RETRACT || slot == DISAPPROVE) plugin.run(player, () -> {
            Integrations.Nation live = government.citizenship(player);
            require(view.nation.equals(live.id()) && view.incumbent.equals(live.leader()),
                "所属国または現職が変わりました。支持率画面を開き直してください。");
            String vote = slot == APPROVE ? "approve" : slot == DISAPPROVE ? "disapprove" : null;
            cast(live, view.owner, vote);
            render(view, live);
        });
    }

    private void cast(Integrations.Nation nation, UUID voter, String vote) {
        require(api.citizens(nation).contains(voter), "現職の国の住民のみ投票できます。");
        String path = path(nation);
        boolean current = current(path, nation.leader());
        if (!current && vote == null) return;
        state.transaction(undo -> {
            if (!current) {
                state.data().set(path, null);
                state.data().set(path + ".incumbent", nation.leader().toString());
            }
            state.data().set(path + ".votes." + voter, vote);
        });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof View) event.setCancelled(true);
    }

    private static final class View implements InventoryHolder {
        private final Inventory inventory;
        private final UUID nation;
        private final UUID incumbent;
        private final UUID owner;

        private View(Integrations.Nation nation, UUID owner) {
            this.nation = nation.id();
            incumbent = nation.leader();
            this.owner = owner;
            inventory = Bukkit.createInventory(this, 27, Component.text("支持率"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
