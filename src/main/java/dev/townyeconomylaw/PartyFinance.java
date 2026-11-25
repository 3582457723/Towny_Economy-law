package dev.townyeconomylaw;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Civics.nationPath;
import static dev.townyeconomylaw.Input.MAX_CASH;
import static dev.townyeconomylaw.Input.credit;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class PartyFinance {
    private static final long GRANT_INTERVAL = Duration.ofDays(30).toMillis();
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z", java.util.Locale.ROOT)
        .withZone(ZoneId.of("Asia/Tokyo"));

    private record Account(String nation, String path, String code) {
        String balance() {
            return path + ".bank";
        }

        String grantAmount() {
            return nation + ".pfpp";
        }
    }

    private final State state;
    private final Integrations api;
    private final Civics civics;
    private final Government government;

    PartyFinance(State state, Integrations api, Civics civics, Government government) {
        this.state = state;
        this.api = api;
        this.civics = civics;
        this.government = government;
    }

    void setGrant(Player player, long amount) {
        Integrations.Nation nation = civics.leader(player);
        require(amount >= 0 && amount <= MAX_CASH, "交付金額の範囲が無効です。");
        state.transaction(undo -> data().set(nationPath(nation.id()) + ".pfpp", amount));
        player.sendMessage(amount == 0 ? "政党交付金を無効にしました。"
            : "政党交付金を " + money(amount) + " に設定しました。");
    }

    void bank(Player player) {
        Account account = account(player);
        String name = data().getString(account.path() + ".name");
        player.sendMessage(name + " [" + account.code() + "] の政党資金 " + money(data().getLong(account.balance())));
        long amount = data().getLong(account.grantAmount());
        if (amount > 0) {
            player.sendMessage("政党交付金 " + money(amount) + " / 国庫残高 " + money(data().getLong(account.nation() + ".treasury")));
            player.sendMessage("政党代表は /tel political_party grant で申請できます。");
        }
    }

    void deposit(Player player, long amount) {
        transfer(player, amount, true);
    }

    void withdrawal(Player player, long amount) {
        transfer(player, amount, false);
    }

    private void transfer(Player player, long amount, boolean deposit) {
        require(amount > 0 && amount <= MAX_CASH, "金額の範囲が無効です。");
        Account account = account(player);
        if (!deposit) requireRepresentative(player, account);
        long balance = data().getLong(account.balance());
        require(deposit || balance >= amount, "政党資金の残高が不足しています。");
        long updated = deposit ? credit(balance, amount) : balance - amount;
        String operation = deposit ? "入金" : "引き出し";
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, !deposit);
            record(account, player, operation, amount, updated);
        });
        player.sendMessage(operation + " " + money(amount) + " / 政党資金残高 " + money(updated));
    }

    void grant(Player player) {
        Account account = account(player);
        requireRepresentative(player, account);
        long amount = data().getLong(account.grantAmount());
        require(amount > 0, "この国では政党交付金が設定されていません。");
        String treasury = account.nation() + ".treasury";
        long funds = data().getLong(treasury);
        require(funds >= amount, "国庫残高が政党交付金額に達していません。");
        UUID claimant = player.getUniqueId();
        String lastPartyGrant = account.nation() + ".pfpp-claims.parties." + account.code();
        String lastRepresentativeGrant = account.nation() + ".pfpp-claims.representatives." + claimant;
        long last = Math.max(data().getLong(lastPartyGrant), data().getLong(lastRepresentativeGrant));
        long now = System.currentTimeMillis();
        require(last == 0 || now - last >= GRANT_INTERVAL, "交付金は同一政党・代表者ともに30日間に1回受給できます。");
        long updated = credit(data().getLong(account.balance()), amount);
        state.transaction(undo -> {
            data().set(treasury, funds - amount);
            data().set(lastPartyGrant, now);
            data().set(lastRepresentativeGrant, now);
            record(account, player, "政党交付金", amount, updated);
        });
        player.sendMessage("国庫から政党交付金 " + money(amount) + " を受給しました。政党資金残高 " + money(updated));
    }

    void history(Player player, int count) {
        require(count >= 1 && count <= 50, "履歴の表示件数は1～50件です。");
        Account account = account(player);
        String ledger = ledger(account);
        if (ledger == null) {
            player.sendMessage("政党資金の取引履歴はありません。");
            return;
        }
        long sequence = data().getLong(ledger + ".sequence");
        player.sendMessage("政党資金の取引履歴 [" + account.code() + "]");
        for (long number = sequence; number > 0 && sequence - number < count; number--) {
            String entry = ledger + ".entries." + number;
            player.sendMessage(HISTORY_TIME.format(Instant.ofEpochMilli(data().getLong(entry + ".time"))) + " / "
                + data().getString(entry + ".actor-name") + " / " + data().getString(entry + ".type")
                + " " + money(data().getLong(entry + ".amount")) + " / 残高 " + money(data().getLong(entry + ".balance")));
        }
    }

    private void record(Account account, Player actor, String operation, long amount, long balance) {
        String ledger = ledger(account);
        if (ledger == null) {
            String id = UUID.randomUUID().toString();
            data().set(account.path() + ".ledger", id);
            ledger = ledger(account);
            data().set(ledger + ".party-code", account.code());
            data().set(ledger + ".party-name", data().getString(account.path() + ".name"));
            data().set(ledger + ".party-owner", data().getString(account.path() + ".owner"));
        }
        data().set(account.balance(), balance);
        long sequence = Math.incrementExact(data().getLong(ledger + ".sequence"));
        String entry = ledger + ".entries." + sequence;
        data().set(entry + ".actor", actor.getUniqueId().toString());
        data().set(entry + ".actor-name", actor.getName());
        data().set(entry + ".time", System.currentTimeMillis());
        data().set(entry + ".type", operation);
        data().set(entry + ".amount", amount);
        data().set(entry + ".balance", balance);
        data().set(ledger + ".sequence", sequence);
    }

    private String ledger(Account account) {
        String id = data().getString(account.path() + ".ledger");
        return id == null ? null : account.nation() + ".party-ledgers." + id;
    }

    private Account account(Player player) {
        Integrations.Nation nation = government.citizenship(player);
        String code = civics.memberParty(nation, player.getUniqueId());
        require(code != null, "政党に所属してください。");
        String root = nationPath(nation.id());
        return new Account(root, root + ".parties." + code, code);
    }

    private void requireRepresentative(Player player, Account account) {
        require(player.getUniqueId().toString().equals(data().getString(account.path() + ".owner")),
            "この操作は政党代表のみ実行できます。");
    }

    private YamlConfiguration data() {
        return state.data();
    }
}
