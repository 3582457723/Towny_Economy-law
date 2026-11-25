package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Auctions {
    record Lot(String id, ItemStack item, UUID seller, UUID sellerNation, UUID bidder, long price, long end,
               Diplomacy.Tariff tariffHeld) {
        long nextBid() {
            return bidder == null ? price : price + 1;
        }

        long escrow() {
            return price + tariffHeld.cents();
        }
    }

    private final State state;
    private final Integrations api;
    private final Diplomacy diplomacy;

    Auctions(State state, Integrations api, Diplomacy diplomacy) {
        this.state = state;
        this.api = api;
        this.diplomacy = diplomacy;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    Set<String> ids() {
        return state.keys("auctions");
    }

    Set<String> claims(UUID owner) {
        return state.keys("claims." + owner);
    }

    Lot lot(String id) {
        String path = "auctions." + id;
        require(data().contains(path), "オークションが見つかりません。");
        UUID seller = UUID.fromString(data().getString(path + ".seller"));
        String sellerNation = data().getString(path + ".seller-nation");
        String tariffNation = data().getString(path + ".tariff-nation");
        return new Lot(id, data().getItemStack(path + ".item"),
            seller, sellerNation == null ? nation(seller) : parseUuid(sellerNation),
            parseUuid(data().getString(path + ".bidder")), data().getLong(path + ".price"), data().getLong(path + ".end"),
            tariffNation == null ? Diplomacy.Tariff.NONE : new Diplomacy.Tariff(UUID.fromString(tariffNation), data().getLong(path + ".tariff")));
    }

    void list(Player player, long minimum, int minutes) {
        PlayerInventory inventory = player.getInventory();
        ItemStack item = inventory.getItemInMainHand().clone();
        require(!item.getType().isAir() && item.getAmount() > 0, "出品するアイテムをメインハンドに持ってください。");
        long next = Math.incrementExact(data().getLong("auction-sequence"));
        String id = "a" + next;
        String path = "auctions." + id;
        UUID sellerNation = nation(player.getUniqueId());
        state.transaction(undo -> {
            inventory.setItemInMainHand(null);
            undo.push(() -> inventory.setItemInMainHand(item));
            data().set("auction-sequence", next);
            data().set(path + ".seller", player.getUniqueId().toString());
            data().set(path + ".seller-nation", sellerNation == null ? "" : sellerNation.toString());
            data().set(path + ".item", item);
            data().set(path + ".price", minimum);
            data().set(path + ".end", System.currentTimeMillis() + minutes * 60_000L);
        });
        player.sendMessage("出品しました。 " + id);
    }

    void bid(Player player, String id, long amount) {
        bid(player, id, amount, null);
    }

    Diplomacy.Tariff tariff(Player player, Lot lot, long amount) {
        return lot.sellerNation() == null ? Diplomacy.Tariff.NONE : diplomacy.tariff(nation(player.getUniqueId()), lot.sellerNation(), amount);
    }

    void bid(Player player, String id, long amount, Diplomacy.Tariff expectedTariff) {
        Lot lot = lot(id);
        UUID bidder = player.getUniqueId();
        require(System.currentTimeMillis() < lot.end(), "このオークションは終了しています。");
        require(!lot.seller().equals(bidder), "自分の出品には入札できません。");
        require(amount >= lot.nextBid() && amount <= Input.MAX_CASH,
            "最低入札額は " + money(lot.nextBid()) + " です。");
        Diplomacy.Tariff tariff = tariff(player, lot, amount);
        require(expectedTariff == null || expectedTariff.equals(tariff), "関税額が更新されました。確認して再入札してください。");
        boolean raising = bidder.equals(lot.bidder());
        long total = amount + tariff.cents();
        long delta = raising ? total - lot.escrow() : total;
        String path = "auctions." + id;
        state.transaction(undo -> {
            api.economy(undo, bidder, Math.abs(delta), delta < 0);
            if (lot.bidder() != null && !raising) addCashClaim(lot.bidder(), id, lot.escrow());
            data().set(path + ".bidder", bidder.toString());
            data().set(path + ".price", amount);
            data().set(path + ".seller-nation", lot.sellerNation() == null ? "" : lot.sellerNation().toString());
            data().set(path + ".tariff-nation", tariff.nation() == null ? null : tariff.nation().toString());
            data().set(path + ".tariff", tariff.nation() == null ? null : tariff.cents());
        });
        player.sendMessage(id + " に " + money(amount) + " で入札しました。"
            + (tariff.cents() == 0 ? "" : " 関税 " + money(tariff.cents()) + " / 合計 " + money(total)));
    }

    void cancel(Player player, String id) {
        Lot lot = lot(id);
        require(lot.seller().equals(player.getUniqueId()), "出品者のみ取り消せます。");
        require(lot.bidder() == null, "入札済みの出品は取り消せません。");
        state.transaction(undo -> settle(lot));
        player.sendMessage("出品を取り消しました。 /tel auction claim " + id + " で回収できます。");
    }

    void finishExpired() {
        long now = System.currentTimeMillis();
        ArrayList<Lot> expired = new ArrayList<>();
        for (String id : ids()) {
            if (data().getLong("auctions." + id + ".end") <= now) expired.add(lot(id));
        }
        if (!expired.isEmpty()) state.transaction(undo -> expired.forEach(this::settle));
    }

    private void settle(Lot lot) {
        UUID recipient = lot.bidder() == null ? lot.seller() : lot.bidder();
        data().set(claim(recipient, lot.id()) + ".item", lot.item());
        if (lot.bidder() != null) {
            addCashClaim(lot.seller(), lot.id(), lot.price());
            diplomacy.collectTariff(lot.tariffHeld());
        }
        data().set("auctions." + lot.id(), null);
    }

    private void addCashClaim(UUID owner, String id, long amount) {
        String path = claim(owner, id) + ".cash";
        data().set(path, Math.addExact(data().getLong(path), amount));
    }

    void claim(Player player, String id) {
        UUID owner = player.getUniqueId();
        String path = claim(owner, id);
        ItemStack item = data().getItemStack(path + ".item");
        long cash = data().getLong(path + ".cash");
        require(item != null || cash > 0, "受け取れる品物・金額がありません。");
        state.transaction(undo -> {
            if (item != null) {
                PlayerInventory inventory = player.getInventory();
                ItemStack[] original = Arrays.stream(inventory.getStorageContents())
                    .map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
                undo.push(() -> inventory.setStorageContents(original));
                require(inventory.addItem(item.clone()).isEmpty(), "インベントリに空きを作ってください。");
            }
            long payout = Math.min(cash, Input.MAX_CASH);
            api.economy(undo, owner, payout, true);
            data().set(path + ".item", null);
            data().set(path + ".cash", cash == payout ? null : cash - payout);
            if (cash == payout) data().set(path, null);
        });
        long payout = Math.min(cash, Input.MAX_CASH);
        player.sendMessage("受け取りました。 " + id + (payout > 0 ? " / " + money(payout) : ""));
        if (cash > payout) player.sendMessage("残額は同じコマンドで受け取れます。");
    }

    private String claim(UUID owner, String id) {
        return "claims." + owner + "." + id;
    }

    private UUID nation(UUID player) {
        Integrations.Nation nation = api.nation(player);
        return nation == null ? null : nation.id();
    }

    private static UUID parseUuid(String id) {
        return id == null || id.isEmpty() ? null : UUID.fromString(id);
    }
}
