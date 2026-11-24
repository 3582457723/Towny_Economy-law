package dev.townyeconomylaw;

import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Input.credit;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Market {
    private static final long CRYPTO_FEE = 10_000_000;
    private static final int MAX_UNITS = 1_000_000;
    private final State state;
    private final Integrations api;

    Market(State state, Integrations api) {
        this.state = state;
        this.api = api;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    void createCompany(Player player, String id, String name) {
        String path = "companies." + id;
        require(!data().contains(path), "その組織 ID は使用済みです。");
        state.transaction(undo -> {
            data().set(path + ".name", name);
            data().set(path + ".leader", player.getUniqueId().toString());
            data().set(path + ".members." + player.getUniqueId(), true);
        });
    }

    Set<String> companies() {
        return state.keys("companies");
    }

    String company(String id) {
        String path = checkCompany(id);
        return data().getString(path + ".name") + " [" + id + "] 代表 "
            + data().getString(path + ".leader") + " / " + state.keys(path + ".members").size() + " 人";
    }

    void invite(Player player, String id, UUID target) {
        String path = leadership(player, id);
        require(!data().getBoolean(path + ".members." + target), "すでに所属しています。");
        state.transaction(undo -> data().set(path + ".invites." + target, true));
    }

    void join(Player player, String id) {
        String path = checkCompany(id);
        UUID member = player.getUniqueId();
        require(data().getBoolean(path + ".invites." + member), "この組織からの招待がありません。");
        state.transaction(undo -> {
            data().set(path + ".invites." + member, null);
            data().set(path + ".members." + member, true);
        });
    }

    void leave(Player player, String id) {
        String path = checkCompany(id);
        UUID member = player.getUniqueId();
        require(!member.toString().equals(data().getString(path + ".leader")), "代表権を移譲してから脱退してください。");
        require(data().getBoolean(path + ".members." + member), "この組織に所属していません。");
        state.transaction(undo -> data().set(path + ".members." + member, null));
    }

    void transfer(Player player, String id, UUID target) {
        String path = leadership(player, id);
        require(data().getBoolean(path + ".members." + target), "移譲先は組織のメンバーである必要があります。");
        state.transaction(undo -> data().set(path + ".leader", target.toString()));
    }

    void list(Player player, String id, String ticker, long base) {
        String company = leadership(player, id);
        require(data().getString(company + ".ticker") == null, "この組織はすでに上場しています。");
        String asset = newAsset(ticker);
        state.transaction(undo -> {
            createAsset(asset, base, id);
            data().set(company + ".ticker", ticker);
        });
    }

    void mint(Player player, String ticker, long base) {
        String asset = newAsset(ticker);
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), CRYPTO_FEE, false);
            createAsset(asset, base, null);
            data().set(asset + ".creator", player.getUniqueId().toString());
        });
    }

    private void createAsset(String asset, long base, String company) {
        data().set(asset + ".base", base);
        data().set(asset + ".supply", 0);
        data().set(asset + ".company", company);
    }

    Set<String> tickers() {
        return state.keys("assets");
    }

    String description(String ticker) {
        String company = data().getString("assets." + ticker + ".company");
        return company == null ? "暗号資産" : "株式 / " + data().getString("companies." + company + ".name");
    }

    int holding(UUID owner, String ticker) {
        return data().getInt("assets." + ticker + ".holdings." + owner);
    }

    String quote(String ticker) {
        String path = asset(ticker);
        long base = data().getLong(path + ".base");
        int supply = data().getInt(path + ".supply");
        String buy = supply == MAX_UNITS ? "売切" : money(value(base, supply, 1));
        String sell = supply == 0 ? "—" : money(value(base, supply - 1, 1));
        return "買 " + buy + " / 売 " + sell + "（税別）";
    }

    void trade(Player player, String ticker, int amount, boolean buy) {
        String path = asset(ticker);
        UUID owner = player.getUniqueId();
        String holdings = path + ".holdings." + owner;
        int count = data().getInt(holdings);
        int supply = data().getInt(path + ".supply");
        require(buy ? supply + amount <= MAX_UNITS : count >= amount,
            buy ? "残りの発行枠が不足しています。" : "保有数が不足しています。");
        long gross = value(data().getLong(path + ".base"), buy ? supply : supply - amount, amount);
        Integrations.Nation nation = api.nation(owner);
        String treasury = nation == null ? null : "nations." + nation.id() + ".treasury";
        long tax = treasury == null ? 0 : fee(gross, data().getLong("nations." + nation.id() + ".tax"));
        long net = buy ? gross + tax : gross - tax;
        require(net <= Input.MAX_CASH, "取引金額が上限を超えています。");
        long taxBalance = tax == 0 ? 0 : credit(data().getLong(treasury), tax);
        int delta = buy ? amount : -amount;
        state.transaction(undo -> {
            api.economy(undo, owner, net, !buy);
            data().set(path + ".supply", supply + delta);
            data().set(holdings, count + delta == 0 ? null : count + delta);
            if (tax != 0) data().set(treasury, taxBalance);
        });
        player.sendMessage((buy ? "購入 " : "売却 ") + ticker + " ×" + amount
            + " / " + money(net) + "（税 " + money(tax) + "）");
    }

    // Sum the marginal prices before changing the circulating supply.
    static long value(long base, int start, int amount) {
        long step = Math.max(1, base / 1_000);
        return base * amount + step * ((2L * start + amount - 1) * amount / 2);
    }

    static long fee(long gross, long basisPoints) {
        return gross / 10_000 * basisPoints + gross % 10_000 * basisPoints / 10_000;
    }

    private String asset(String ticker) {
        String path = "assets." + ticker;
        require(data().contains(path), "銘柄が見つかりません。");
        return path;
    }

    private String newAsset(String ticker) {
        String path = "assets." + ticker;
        require(!data().contains(path), "その銘柄コードは使用済みです。");
        return path;
    }

    private String checkCompany(String id) {
        String path = "companies." + id;
        require(data().contains(path), "組織が見つかりません。");
        return path;
    }

    private String leadership(Player player, String id) {
        String path = checkCompany(id);
        require(player.getUniqueId().toString().equals(data().getString(path + ".leader")), "組織の代表者のみ操作できます。");
        return path;
    }
}
