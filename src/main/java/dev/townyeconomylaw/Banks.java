package dev.townyeconomylaw;

import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Input.credit;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Banks {
    private final State state;
    private final Integrations api;

    Banks(State state, Integrations api) {
        this.state = state;
        this.api = api;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    Set<String> ids() {
        return state.keys("banks");
    }

    void create(Player player, String id, String name) {
        String path = "banks." + id;
        require(!data().contains(path), "その銀行 ID は使用済みです。");
        state.transaction(undo -> {
            data().set(path + ".owner", player.getUniqueId().toString());
            data().set(path + ".name", name);
        });
    }

    void info(Player player, String id) {
        String path = bank(id);
        player.sendMessage(data().getString(path + ".name") + " [" + id + "] 入金手数料 "
            + money(data().getLong(path + ".fee")) + "% / 自分の残高 "
            + money(data().getLong(path + ".balances." + player.getUniqueId())));
        if (owns(player, path)) {
            player.sendMessage("受取可能な手数料 " + money(data().getLong(path + ".revenue")));
        }
    }

    void move(Player player, String id, long amount, boolean deposit) {
        String path = bank(id);
        UUID account = player.getUniqueId();
        String key = path + ".balances." + account;
        String revenue = path + ".revenue";
        long balance = data().getLong(key);
        require(deposit || balance >= amount, "銀行残高が不足しています。");
        long charge = deposit ? Market.fee(amount, data().getLong(path + ".fee")) : 0;
        long updated = deposit ? credit(balance, amount - charge) : balance - amount;
        long proceeds = credit(data().getLong(revenue), charge);
        state.transaction(undo -> {
            api.economy(undo, account, amount, !deposit);
            data().set(key, updated == 0 ? null : updated);
            if (charge > 0) data().set(revenue, proceeds);
        });
        player.sendMessage("銀行残高 " + money(updated) + " / 手数料 " + money(charge));
    }

    void fee(Player player, String id, long fee) {
        String path = bank(id);
        require(owns(player, path), "銀行の所有者のみ操作できます。");
        state.transaction(undo -> data().set(path + ".fee", fee));
    }

    void collect(Player player, String id) {
        String path = bank(id);
        require(owns(player, path), "銀行の所有者のみ操作できます。");
        String revenue = path + ".revenue";
        long amount = data().getLong(revenue);
        require(amount > 0, "受け取れる手数料がありません。");
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, true);
            data().set(revenue, null);
        });
        player.sendMessage("手数料を受け取りました。 " + money(amount));
    }

    private boolean owns(Player player, String path) {
        return player.getUniqueId().toString().equals(data().getString(path + ".owner"));
    }

    private String bank(String id) {
        String path = "banks." + id;
        require(data().contains(path), "銀行が見つかりません。");
        return path;
    }
}
