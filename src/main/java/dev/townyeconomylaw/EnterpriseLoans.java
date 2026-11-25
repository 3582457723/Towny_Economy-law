package dev.townyeconomylaw;

import java.util.Comparator;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.Input.credit;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class EnterpriseLoans {
    private static final int BASIS_POINTS_PER_WHOLE = 10_000;

    private final State state;
    private final Integrations api;
    private final Enterprises firms;

    EnterpriseLoans(State state, Integrations api, Enterprises firms) {
        this.state = state;
        this.api = api;
        this.firms = firms;
    }

    private static String lending(Enterprises.Firm firm) {
        return Enterprises.path(firm) + ".lending";
    }

    private String debt(Enterprises.Firm firm, Player borrower) {
        return lending(firm) + ".borrowers." + borrower.getUniqueId();
    }

    private YamlConfiguration data() {
        return state.data();
    }

    void setRate(Player player, Enterprises.Firm firm, String percent) {
        firms.leader(player, firm);
        String number = percent.endsWith("%") ? percent.substring(0, percent.length() - 1) : percent;
        long rate = Input.cents(number, 0, BASIS_POINTS_PER_WHOLE);
        state.transaction(undo -> data().set(lending(firm) + ".rate", rate));
        player.sendMessage(firm.code() + " の新規貸付金利を " + money(rate) + "% に設定しました。");
    }

    void fund(Player player, Enterprises.Firm firm, long amount) {
        firms.leader(player, firm);
        require(amount > 0, "入金額は0より大きくしてください。");
        String ledger = lending(firm);
        long pool = credit(data().getLong(ledger + ".capital"), amount);
        credit(pool, data().getLong(ledger + ".outstanding"));
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, false);
            data().set(ledger + ".capital", pool);
        });
        player.sendMessage("貸付原資に " + money(amount) + " を入金しました。貸付可能額 " + money(pool));
    }

    void withdraw(Player player, Enterprises.Firm firm, long amount) {
        firms.leader(player, firm);
        require(amount > 0, "出金額は0より大きくしてください。");
        String capital = lending(firm) + ".capital";
        long pool = data().getLong(capital);
        require(amount <= pool, "貸付原資の残高が不足しています。貸付可能額 " + money(pool));
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, true);
            data().set(capital, pool - amount);
        });
        player.sendMessage("貸付原資から " + money(amount) + " を出金しました。貸付可能額 " + money(pool - amount));
    }

    void lenderInfo(Player player, Enterprises.Firm firm) {
        firms.leader(player, firm);
        String ledger = lending(firm);
        player.sendMessage(firm.name() + " [" + firm.code() + "] / 新規貸付金利 "
            + money(data().getLong(ledger + ".rate")) + "% / 貸付可能額 " + money(data().getLong(ledger + ".capital"))
            + " / 返済待ち " + money(data().getLong(ledger + ".outstanding")));
    }

    void borrow(Player player, Enterprises.Firm firm, long amount) {
        require(amount > 0, "借入額は0より大きくしてください。");
        String ledger = lending(firm);
        long pool = data().getLong(ledger + ".capital");
        require(amount <= pool, "この組織の貸付原資が不足しています。貸付可能額 " + money(pool));

        long rate = data().getLong(ledger + ".rate");
        long interest = Math.ceilDiv(amount * rate, BASIS_POINTS_PER_WHOLE);
        long due = credit(amount, interest);
        String borrower = debt(firm, player);
        long remaining = credit(data().getLong(borrower), due);
        long outstanding = credit(data().getLong(ledger + ".outstanding"), due);
        long available = pool - amount;
        // The available pool plus all receivables must fit in the same balance when the loans are repaid.
        credit(available, outstanding);
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, true);
            data().set(ledger + ".capital", available);
            data().set(ledger + ".outstanding", outstanding);
            data().set(borrower, remaining);
        });
        player.sendMessage(firm.code() + " から " + money(amount) + " を借りました。今回の利息 " + money(interest)
            + "（" + money(rate) + "%） / 返済残額 " + money(remaining));
    }

    void repay(Player player, Enterprises.Firm firm, long amount) {
        require(amount > 0, "返済額は0より大きくしてください。");
        String ledger = lending(firm);
        String borrower = debt(firm, player);
        long due = data().getLong(borrower);
        require(due > 0, "この組織からの借入はありません。");
        require(amount <= due, "返済額は未返済額 " + money(due) + " 以下にしてください。");
        long remaining = due - amount;
        long pool = credit(data().getLong(ledger + ".capital"), amount);
        long outstanding = data().getLong(ledger + ".outstanding") - amount;
        require(outstanding >= 0, "貸付台帳の残高が一致していません。管理者に連絡してください。");
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, false);
            data().set(ledger + ".capital", pool);
            data().set(ledger + ".outstanding", outstanding);
            data().set(borrower, remaining == 0 ? null : remaining);
        });
        player.sendMessage(firm.code() + " に " + money(amount) + " を返済しました。返済残額 " + money(remaining));
    }

    void borrowerInfo(Player player) {
        List<Enterprises.Firm> lenders = state.keys("enterprises").stream().map(firms::get)
            .filter(firm -> firm != null && data().getLong(debt(firm, player)) > 0)
            .sorted(Comparator.comparing(Enterprises.Firm::code))
            .toList();
        if (lenders.isEmpty()) {
            player.sendMessage("返済が必要な借入はありません。");
            return;
        }
        for (Enterprises.Firm firm : lenders) {
            player.sendMessage(firm.name() + " [" + firm.code() + "] / 返済残額 " + money(data().getLong(debt(firm, player))));
        }
    }

    static void requireClear(State state, Enterprises.Firm firm) {
        String ledger = lending(firm);
        require(state.data().getLong(ledger + ".outstanding") == 0 && state.keys(ledger + ".borrowers").isEmpty(),
            "未返済の貸付がある組織は解散できません。");
        require(state.data().getLong(ledger + ".capital") == 0, "貸付原資を出金してから解散してください。");
    }
}
