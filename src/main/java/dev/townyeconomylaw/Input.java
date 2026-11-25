package dev.townyeconomylaw;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.State.require;

final class Input {
    private static final Pattern ARGUMENT = Pattern.compile("\"([^\"]*)\"|([^\\s\"]+)");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_-]{2,24}");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,13}(\\.[0-9]{1,2})?");
    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,7}");

    static final long MAX_CASH = 100_000_000_000_000L;

    private Input() {}

    static String key(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        require(KEY.matcher(normalized).matches(), "ID は半角英数字・_・- の2〜24文字です。");
        return normalized;
    }

    static String value(String[] args, int index) {
        require(args.length > index, "引数が足りません。/tel help で使い方を表示します。");
        return args[index];
    }

    static String text(String[] args, int start, int max) {
        value(args, start);
        String result = String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
        require(!result.isBlank() && result.length() <= max, "本文は1〜" + max + "文字です。");
        return result;
    }

    static long cents(String value, long min, long max) {
        require(DECIMAL.matcher(value).matches(), "金額・割合は小数第2位まで入力できます。");
        long amount = new BigDecimal(value).movePointRight(2).longValueExact();
        require(amount >= min && amount <= max, "入力範囲は " + money(min) + "〜" + money(max) + " です。");
        return amount;
    }

    static int number(String value, int min, int max) {
        require(INTEGER.matcher(value).matches(), "数量は整数で指定してください。");
        int number = Integer.parseInt(value);
        require(number >= min && number <= max, "入力範囲は " + min + "〜" + max + " です。");
        return number;
    }

    static String money(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    static OfflinePlayer knownPlayer(String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) return player;
        }
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(player.getName())) return player;
        }
        throw new State.Rejected("プレイヤーが見つかりません。");
    }

    static long credit(long balance, long amount) {
        long result = Math.addExact(balance, amount);
        require(result >= 0 && result <= MAX_CASH, "残高上限を超えるため処理できません。");
        return result;
    }
    static List<String> arguments(String[] input) {
        String joined = String.join(" ", input);
        var matcher = ARGUMENT.matcher(joined);
        List<String> result = new ArrayList<>();
        int previous = 0;
        while (matcher.find()) {
            require((previous == 0 || matcher.start() > previous) && joined.substring(previous, matcher.start()).isBlank(),
                "空白を含む引数は二重引用符で囲んでください。");
            result.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            previous = matcher.end();
        }
        require(joined.substring(previous).isBlank(), "二重引用符を閉じてください。");
        return result;
    }
}
