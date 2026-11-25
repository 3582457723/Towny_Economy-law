package dev.townyeconomylaw;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import static dev.townyeconomylaw.Input.MAX_CASH;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class BlackMarket {
    record Offer(String id, ItemStack item, long price, Diplomacy.Tariff tariff, boolean own, boolean restricted) {
        long total() {
            return Math.addExact(price, tariff.cents());
        }
    }

    private record Listing(String id, ItemStack item, long price, UUID seller, UUID sellerNation, UUID recipient) {
        boolean visible(UUID player) {
            return recipient == null || seller.equals(player) || recipient.equals(player);
        }
    }

    private static final String ROOT = "black-market";
    private final State state;
    private final Integrations api;
    private final Diplomacy diplomacy;

    BlackMarket(State state, Integrations api, Diplomacy diplomacy) {
        this.state = state;
        this.api = api;
        this.diplomacy = diplomacy;
    }

    void list(Player seller, long price) {
        list(seller, null, price);
    }

    void trade(Player seller, UUID buyer, long price) {
        require(!seller.getUniqueId().equals(buyer), "自分宛ての取引は出品できません。");
        String id = list(seller, buyer, price);
        Player recipient = Bukkit.getPlayer(buyer);
        if (recipient != null) recipient.sendMessage("あなた宛ての闇取引 " + id + " が届きました。 /tel blackmarket");
    }

    private String list(Player seller, UUID buyer, long price) {
        require(price > 0 && price <= MAX_CASH, "出品価格の範囲が無効です。");
        PlayerInventory inventory = seller.getInventory();
        ItemStack item = inventory.getItemInMainHand().clone();
        require(!item.getType().isAir() && item.getAmount() > 0, "出品するアイテムをメインハンドに持ってください。");
        UUID actor = seller.getUniqueId();
        UUID sellerNation = nation(actor);
        long number = Math.incrementExact(data().getLong(ROOT + ".sequence"));
        String id = "m" + number;
        String entry = listingPath(id);
        state.transaction(undo -> {
            inventory.setItemInMainHand(null);
            undo.push(() -> inventory.setItemInMainHand(item));
            data().set(ROOT + ".sequence", number);
            data().set(entry + ".item", item);
            data().set(entry + ".price", price);
            data().set(entry + ".seller", actor.toString());
            data().set(entry + ".seller-nation", sellerNation == null ? null : sellerNation.toString());
            data().set(entry + ".recipient", buyer == null ? null : buyer.toString());
        });
        seller.sendMessage((buyer == null ? "闇市に出品しました。 " : "相手限定の闇取引に出品しました。 ") + id + " / " + money(price));
        return id;
    }

    List<Offer> offers(Player player) {
        UUID viewer = player.getUniqueId();
        UUID country = nation(viewer);
        return state.keys(ROOT + ".listings").stream()
            .sorted(Comparator.comparingLong((String id) -> Long.parseLong(id.substring(1))).reversed())
            .map(this::listing)
            .filter(lot -> lot.visible(viewer))
            .filter(lot -> lot.seller().equals(viewer) || !blocked(lot, country))
            .map(lot -> offer(lot, viewer, country))
            .toList();
    }

    void buy(Player player, String id) {
        buy(player, id, null);
    }

    void buy(Player player, String id, Offer expected) {
        UUID buyer = player.getUniqueId();
        Listing listing = listing(id);
        require(listing.visible(buyer), "闇市の出品が見つかりません。");
        require(!listing.seller().equals(buyer), "自分の出品は購入できません。");
        UUID country = nation(buyer);
        require(!blocked(listing, country), "経済制裁によりこの取引はできません。");
        Offer offer = offer(listing, buyer, country);
        require(expected == null || (id.equals(expected.id()) && offer.price() == expected.price()
            && offer.tariff().equals(expected.tariff())), "取引条件が変わりました。闇市を開き直してください。");
        state.transaction(undo -> {
            data().set(listingPath(id), null);
            api.economy(undo, buyer, offer.total(), false);
            data().set(claimPath(buyer, id) + ".item", listing.item());
            data().set(claimPath(listing.seller(), id) + ".cash", offer.price());
            diplomacy.collectTariff(offer.tariff());
        });
        player.sendMessage("闇取引が成立しました。 " + id + " / 合計 " + money(offer.total()) + "。 /tel blackmarket claim " + id);
    }

    void cancel(Player seller, String id) {
        Listing listing = listing(id);
        UUID actor = seller.getUniqueId();
        require(listing.visible(actor), "闇市の出品が見つかりません。");
        require(listing.seller().equals(actor), "出品者のみ取り消せます。");
        state.transaction(undo -> {
            data().set(listingPath(id), null);
            data().set(claimPath(actor, id) + ".item", listing.item());
        });
        seller.sendMessage("出品を取り消しました。 /tel blackmarket claim " + id);
    }

    Set<String> claims(UUID owner) {
        return state.keys(ROOT + ".claims." + owner);
    }

    void claim(Player player, String id) {
        UUID owner = player.getUniqueId();
        String claim = claimPath(owner, id);
        ItemStack item = data().getItemStack(claim + ".item");
        long cash = data().getLong(claim + ".cash");
        require(item != null || cash > 0, "受け取れる品物・金額がありません。");
        state.transaction(undo -> {
            data().set(claim, null);
            if (item != null) {
                PlayerInventory inventory = player.getInventory();
                ItemStack[] before = Arrays.stream(inventory.getStorageContents())
                    .map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
                undo.push(() -> inventory.setStorageContents(before));
                require(inventory.addItem(item.clone()).isEmpty(), "インベントリに空きを作ってください。");
            }
            api.economy(undo, owner, cash, true);
        });
        player.sendMessage("取引分を受け取りました。 " + id + (cash == 0 ? "" : " / " + money(cash)));
    }

    private boolean blocked(Listing listing, UUID buyerCountry) {
        return diplomacy.tradeBlocked(buyerCountry, listing.sellerNation())
            || diplomacy.tradeBlocked(buyerCountry, nation(listing.seller()));
    }

    private Offer offer(Listing listing, UUID viewer, UUID country) {
        boolean own = listing.seller().equals(viewer);
        Diplomacy.Tariff tariff = own ? Diplomacy.Tariff.NONE : diplomacy.tariff(country, listing.sellerNation(), listing.price());
        return new Offer(listing.id(), listing.item(), listing.price(), tariff, own, listing.recipient() != null);
    }

    private Listing listing(String id) {
        require(id.matches("m[1-9][0-9]{0,18}"), "闇市の出品IDが無効です。");
        String entry = listingPath(id);
        String seller = data().getString(entry + ".seller");
        require(seller != null, "闇市の出品が見つかりません。");
        String sellerNation = data().getString(entry + ".seller-nation");
        String recipient = data().getString(entry + ".recipient");
        return new Listing(id, data().getItemStack(entry + ".item"), data().getLong(entry + ".price"),
            UUID.fromString(seller), sellerNation == null ? null : UUID.fromString(sellerNation),
            recipient == null ? null : UUID.fromString(recipient));
    }

    private UUID nation(UUID player) {
        Integrations.Nation nation = api.nation(player);
        return nation == null ? null : nation.id();
    }

    private YamlConfiguration data() {
        return state.data();
    }

    private String listingPath(String id) {
        return ROOT + ".listings." + id;
    }

    private String claimPath(UUID owner, String id) {
        return ROOT + ".claims." + owner + "." + id;
    }
}
