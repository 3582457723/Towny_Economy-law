package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

final class ElectionMenu implements Listener {
    private static final int PAGE = 45;

    private final TownyEconomyLaw plugin;
    private final Civics civics;
    private final Government government;

    ElectionMenu(TownyEconomyLaw plugin, Civics civics, Government government) {
        this.plugin = plugin;
        this.civics = civics;
        this.government = government;
    }

    void open(Player player) {
        View view = new View(civics.ballotNation(player), player.getUniqueId());
        render(player, view);
        player.openInventory(view.inventory);
    }

    private void render(Player player, View view) {
        List<Civics.Ballot> ballots = civics.ballots(player, view.nation);
        view.inventory.clear();
        view.actions.clear();
        if (view.selected == null) {
            renderList(view, ballots);
        } else {
            Civics.Ballot ballot = ballots.stream().filter(candidate -> candidate.id().equals(view.selected)).findFirst()
                .orElseThrow(() -> new State.Rejected("選挙が見つかりません。"));
            renderBallot(player, view, ballot);
        }
    }

    private void renderList(View view, List<Civics.Ballot> ballots) {
        for (int index = pageStart(view, ballots.size()), slot = 0; index < ballots.size() && slot < PAGE; index++, slot++) {
            Civics.Ballot ballot = ballots.get(index);
            put(view, slot, ballot.open() ? Material.PAPER : Material.BOOK, ballot.type(),
                List.of(ballot.content(), ballot.status(), "クリックで詳細"), () -> {
                    view.selected = ballot.id();
                    view.listPage = view.page;
                    view.page = 0;
                });
        }
        if (ballots.isEmpty()) put(view, 22, Material.BARRIER, "選挙はありません", List.of(), null);
        addPages(view, ballots.size());
    }

    private void renderBallot(Player player, View view, Civics.Ballot ballot) {
        List<String> details = new ArrayList<>();
        details.add(ballot.status());
        details.add(ballot.content());
        details.addAll(ballot.details());
        details.add("投票 ID " + ballot.id());
        if (ballot.open() && !ballot.entitled()) details.add("この選挙の投票権がありません");
        put(view, 47, Material.WRITTEN_BOOK, ballot.type(), details, null);
        put(view, 49, Material.OAK_DOOR, "一覧に戻る", List.of(), () -> {
            view.selected = null;
            view.page = view.listPage;
        });
        if (ballot.canStand()) put(view, 51, Material.FEATHER, "立候補する", List.of(), () -> civics.stand(player, ballot.id()));
        List<Civics.BallotChoice> choices = ballot.choices();
        for (int index = pageStart(view, choices.size()), slot = 0; index < choices.size() && slot < PAGE; index++, slot++) {
            Civics.BallotChoice choice = choices.get(index);
            Material material = switch (choice.value()) {
                case "yes" -> Material.LIME_DYE;
                case "no" -> Material.RED_DYE;
                default -> Material.NAME_TAG;
            };
            boolean canVote = ballot.open() && ballot.entitled();
            List<String> lore = choice.selected() ? List.of("自分の投票先") : canVote ? List.of("クリックで投票") : List.of();
            put(view, slot, material, choice.label(), lore, canVote ? () -> civics.vote(player, ballot.id(), choice.value()) : null);
        }
        if (choices.isEmpty()) put(view, 22, Material.BARRIER, "立候補者はいません", List.of(), null);
        addPages(view, choices.size());
    }

    private int pageStart(View view, int size) {
        view.page = Math.min(view.page, Math.max(0, (size - 1) / PAGE));
        return view.page * PAGE;
    }

    private void addPages(View view, int size) {
        if (view.page > 0) put(view, 45, Material.ARROW, "前のページ", List.of(), () -> view.page--);
        if ((view.page + 1) * PAGE < size) put(view, 53, Material.ARROW, "次のページ", List.of(), () -> view.page++);
    }

    private void put(View view, int slot, Material material, String title, List<String> description, Runnable action) {
        ItemStack icon = new ItemStack(material);
        var meta = icon.getItemMeta();
        meta.displayName(line(title));
        List<Component> lore = new ArrayList<>();
        for (String paragraph : description) {
            int[] codePoints = paragraph.codePoints().toArray();
            for (int from = 0; from < codePoints.length; from += 30) {
                lore.add(line(new String(codePoints, from, Math.min(30, codePoints.length - from))));
            }
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        view.inventory.setItem(slot, icon);
        if (action != null) view.actions.put(slot, action);
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
            || !view.owner.equals(player.getUniqueId()) || government.jailed(player) || !player.hasPermission("townyeconomylaw.use")) return;
        Runnable action = view.actions.get(event.getRawSlot());
        if (action != null) plugin.run(player, () -> {
            require(view.nation.equals(civics.ballotNation(player)), "所属国が変更されました。選挙一覧を開き直してください。");
            action.run();
            render(player, view);
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
        private final UUID owner;
        private final Map<Integer, Runnable> actions = new HashMap<>();
        private String selected;
        private int page;
        private int listPage;

        private View(UUID nation, UUID owner) {
            this.nation = nation;
            this.owner = owner;
            this.inventory = Bukkit.createInventory(this, 54, Component.text("選挙・投票"));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
