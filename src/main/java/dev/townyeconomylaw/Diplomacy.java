package dev.townyeconomylaw;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import static dev.townyeconomylaw.Civics.nationPath;
import static dev.townyeconomylaw.Input.money;
import static dev.townyeconomylaw.State.require;

final class Diplomacy implements Listener {
    record Tariff(UUID nation, long cents) {
        static final Tariff NONE = new Tariff(null, 0);
    }

    private enum Document {
        PASSPORT("passport", "パスポート"),
        VISA("visa", "ビザ");

        final String key;
        final String title;

        Document(String key, String title) {
            this.key = key;
            this.title = title;
        }
    }

    private final State state;
    private final Integrations api;
    private final Government government;
    private boolean hasVisaBorders;

    Diplomacy(State state, Integrations api, Government government) {
        this.state = state;
        this.api = api;
        this.government = government;
        refreshBorders();
    }

    void request(Player player, String[] args) {
        List<String> words = Input.arguments(args);
        String action = word(words, 0).toLowerCase(Locale.ROOT);
        if ("status".equals(action)) {
            require(words.size() == 1, "使い方 /request status");
            status(player);
            return;
        }
        require("passport".equals(action) || "visa".equals(action), "使い方 /request <passport|visa [国名]|status>");
        Document document = "passport".equals(action) ? Document.PASSPORT : Document.VISA;
        UUID applicant = player.getUniqueId();
        Integrations.Nation home = government.citizenship(player);
        Integrations.Nation issuer = home;
        if (document == Document.VISA) {
            require(words.size() <= 2, "使い方 /request visa [国名]");
            require(validPassport(applicant, home.id()), "自国から発行された有効なパスポートが必要です。");
            issuer = words.size() == 2 ? nation(word(words, 1)) : api.plotNation(player.getLocation());
            require(issuer != null && !issuer.id().equals(home.id()), "訪問先の国名を指定してください。 /request visa <国名>");
        } else {
            require(words.size() == 1, "使い方 /request passport");
        }
        String path = credentials(issuer.id(), document, applicant);
        require(!"pending".equals(data().getString(path + ".status")), "すでに審査中の申請があります。");
        require(!validCredential(issuer.id(), document, applicant, home.id()), "有効な" + document.title + "が発行されています。");
        state.transaction(undo -> {
            data().set(path + ".status", "pending");
            data().set(path + ".origin", home.id().toString());
        });
        player.sendMessage(issuer.name() + " に" + document.title + "を申請しました。");
    }

    void nation(Player player, String[] args) {
        List<String> words = Input.arguments(args);
        switch (word(words, 1).toLowerCase(Locale.ROOT)) {
            case "passport" -> decide(player, Document.PASSPORT, words);
            case "visa" -> decide(player, Document.VISA, words);
            case "tariffs" -> tariffs(player, words);
            case "fta" -> tradeAgreement(player, words);
            case "border" -> border(player, words);
            default -> throw new State.Rejected("使い方 /tel nation <passport|visa|tariffs|fta|border>");
        }
    }

    private void decide(Player player, Document document, List<String> words) {
        Integrations.Nation nation = official(player, Government.Role.MINISTER);
        String action = word(words, 2).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            require(words.size() == 3, "使い方 /tel nation " + document.key + " list");
            List<String> pending = state.keys(credentials(nation.id(), document)).stream()
                .filter(id -> "pending".equals(data().getString(credentials(nation.id(), document) + "." + id + ".status")))
                .map(UUID::fromString).map(Bukkit::getOfflinePlayer)
                .map(applicant -> Objects.toString(applicant.getName(), applicant.getUniqueId().toString())).sorted().toList();
            player.sendMessage("審査中の" + document.title + (pending.isEmpty() ? "はありません。" : " / " + String.join(", ", pending)));
            return;
        }
        require(words.size() == 4 && List.of("agree", "deny", "revoke").contains(action),
            "使い方 /tel nation " + document.key + " <agree|deny|revoke> <MCID>");
        OfflinePlayer applicant = Input.knownPlayer(word(words, 3));
        UUID owner = applicant.getUniqueId();
        String path = credentials(nation.id(), document, owner);
        boolean revoke = "revoke".equals(action);
        require((revoke ? "approved" : "pending").equals(data().getString(path + ".status")),
            revoke ? "取り消せる発行記録がありません。" : "審査中の申請がありません。");
        if ("agree".equals(action)) {
            Integrations.Nation home = api.nation(owner);
            require(home != null && home.id().toString().equals(data().getString(path + ".origin")),
                "申請者の所属国が変わりました。却下して再申請を案内してください。");
            if (document == Document.VISA) require(validPassport(owner, home.id()), "申請者のパスポートが有効になっていません。");
        }
        String decision = "agree".equals(action) ? "approved" : revoke ? "revoked" : "denied";
        state.transaction(undo -> data().set(path + ".status", decision));
        String result = "approved".equals(decision) ? "発行しました。" : revoke ? "取り消しました。" : "却下しました。";
        player.sendMessage(applicant.getName() + " の" + document.title + "を" + result);
        Player online = applicant.getPlayer();
        if (online != null && !online.equals(player)) online.sendMessage(nation.name() + " があなたの" + document.title + "を" + result);
    }

    private void status(Player player) {
        UUID owner = player.getUniqueId();
        Integrations.Nation home = api.nation(owner);
        int found = 0;
        for (String issuer : state.keys("nations")) {
            UUID country = UUID.fromString(issuer);
            for (Document document : Document.values()) {
                String path = credentials(country, document, owner);
                String status = data().getString(path + ".status");
                if (status == null) continue;
                Integrations.Nation nation = api.nationById(country);
                boolean valid = nation != null && home != null && (document == Document.PASSPORT
                    ? country.equals(home.id()) && validPassport(owner, home.id())
                    : validVisa(owner, home.id(), country));
                String text = switch (status) {
                    case "pending" -> "審査中";
                    case "denied" -> "却下";
                    case "revoked" -> "取消";
                    default -> valid ? "有効" : "無効";
                };
                player.sendMessage(document.title + " / " + (nation == null ? "消滅した国" : nation.name()) + " / " + text);
                found++;
            }
        }
        if (found == 0) player.sendMessage("渡航書類の申請・発行記録はありません。");
    }

    private static String credentials(UUID country, Document document) {
        return nationPath(country) + ".diplomacy." + document.key;
    }

    private static String credentials(UUID country, Document document, UUID applicant) {
        return credentials(country, document) + "." + applicant;
    }

    private boolean validCredential(UUID issuer, Document document, UUID owner, UUID origin) {
        String path = credentials(issuer, document, owner);
        return "approved".equals(data().getString(path + ".status")) && origin.toString().equals(data().getString(path + ".origin"));
    }

    private boolean validPassport(UUID owner, UUID home) {
        return validCredential(home, Document.PASSPORT, owner, home);
    }

    private boolean validVisa(UUID owner, UUID home, UUID foreign) {
        return validPassport(owner, home) && validCredential(foreign, Document.VISA, owner, home);
    }

    private Integrations.Nation official(Player player, Government.Role role) {
        Integrations.Nation nation = government.citizenship(player);
        UUID id = player.getUniqueId();
        require(id.equals(nation.leader()) || government.hasRole(nation, id, Government.Role.MINISTER)
            || government.hasRole(nation, id, role), "国のリーダーまたは担当の役職のみ実行できます。");
        return nation;
    }

    private Integrations.Nation nation(String name) {
        Integrations.Nation nation = api.nation(name);
        require(nation != null, "指定した国が見つかりません。");
        return nation;
    }

    private void tariffs(Player player, List<String> words) {
        Integrations.Nation nation = official(player, Government.Role.TREASURER);
        require(words.size() == 3, "使い方 /tel nation tariffs <0〜100>");
        String value = word(words, 2);
        long rate = Input.cents(value.endsWith("%") ? value.substring(0, value.length() - 1) : value, 0, 10_000);
        state.transaction(undo -> data().set(tariffPath(nation.id()), rate));
        player.sendMessage(nation.name() + " の輸入関税を " + money(rate) + "% に設定しました。");
    }

    private static String tariffPath(UUID nation) {
        return nationPath(nation) + ".diplomacy.tariff";
    }

    Tariff tariff(UUID importer, UUID exporter, long price) {
        if (importer == null || exporter == null || importer.equals(exporter)
            || data().getBoolean(treatyPath(importer, exporter) + ".active")) return Tariff.NONE;
        long rate = data().getLong(tariffPath(importer));
        long cents = Market.fee(price, rate);
        return cents == 0 ? Tariff.NONE : new Tariff(importer, cents);
    }

    void collectTariff(Tariff tariff) {
        if (tariff.cents() == 0) return;
        String treasury = nationPath(tariff.nation()) + ".treasury";
        data().set(treasury, Math.addExact(data().getLong(treasury), tariff.cents()));
    }

    private void tradeAgreement(Player player, List<String> words) {
        String action = word(words, 2).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            require(words.size() == 3, "使い方 /tel nation fta list");
            treaties(player);
            return;
        }
        Integrations.Nation ours = official(player, Government.Role.MINISTER);
        require(words.size() == 4 && List.of("offer", "accept", "revoke").contains(action),
            "使い方 /tel nation fta <offer|accept|revoke> <国名>");
        Integrations.Nation other = nation(word(words, 3));
        require(!ours.id().equals(other.id()), "他国を指定してください。");
        String path = treatyPath(ours.id(), other.id());
        String offered = data().getString(path + ".offer");
        boolean active = data().getBoolean(path + ".active");
        switch (action) {
            case "offer" -> {
                require(!active && offered == null, "締結済み、または提案中の協定があります。");
                state.transaction(undo -> data().set(path + ".offer", ours.id().toString()));
                player.sendMessage(other.name() + " に自由貿易協定を提案しました。");
            }
            case "accept" -> {
                require(!active && other.id().toString().equals(offered), "相手国からの協定の提案がありません。");
                state.transaction(undo -> {
                    data().set(path + ".offer", null);
                    data().set(path + ".active", true);
                });
                player.sendMessage(other.name() + " と自由貿易協定を締結しました。");
            }
            default -> {
                require(active || offered != null, "対象国との協定・提案が見つかりません。");
                state.transaction(undo -> data().set(path, null));
                player.sendMessage(other.name() + (active ? " との協定を解除しました。" : " との協定の提案を取り消しました。"));
            }
        }
    }

    private void treaties(Player player) {
        String own = government.citizenship(player).id().toString();
        int found = 0;
        for (String pair : state.keys("free-trade")) {
            String[] parties = pair.split(":", 2);
            String foreign = parties[0].equals(own) ? parties[1] : parties[1].equals(own) ? parties[0] : null;
            if (foreign == null) continue;
            Integrations.Nation nation = api.nationById(UUID.fromString(foreign));
            if (nation == null) continue;
            String path = "free-trade." + pair;
            if (!data().getBoolean(path + ".active") && !data().contains(path + ".offer")) continue;
            String status = data().getBoolean(path + ".active") ? "締結済み"
                : own.equals(data().getString(path + ".offer")) ? "提案中" : "承認待ち";
            player.sendMessage(nation.name() + " / " + status);
            found++;
        }
        if (found == 0) player.sendMessage("自由貿易協定・提案はありません。");
    }

    private static String treatyPath(UUID first, UUID second) {
        return first.compareTo(second) < 0 ? "free-trade." + first + ":" + second : "free-trade." + second + ":" + first;
    }

    private void border(Player player, List<String> words) {
        require(words.size() == 3, "使い方 /tel nation border <open|visa|status>");
        String action = word(words, 2).toLowerCase(Locale.ROOT);
        if ("status".equals(action)) {
            Integrations.Nation country = government.citizenship(player);
            player.sendMessage(country.name() + " の入国条件 / " + (data().getBoolean(borderPath(country.id())) ? "ビザ必須" : "公開"));
            return;
        }
        require("open".equals(action) || "visa".equals(action), "使い方 /tel nation border <open|visa|status>");
        Integrations.Nation country = official(player, Government.Role.MINISTER);
        state.transaction(undo -> data().set(borderPath(country.id()), "visa".equals(action)));
        refreshBorders();
        player.sendMessage(country.name() + " の入国条件を更新しました。");
    }

    private void refreshBorders() {
        hasVisaBorders = state.keys("nations").stream().map(UUID::fromString).anyMatch(id -> data().getBoolean(borderPath(id)));
    }

    private static String borderPath(UUID nation) {
        return nationPath(nation) + ".diplomacy.visa-border";
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (hasVisaBorders && denied(event.getPlayer(), event.getFrom(), event.getTo())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        move(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(PlayerPortalEvent event) {
        move(event);
    }

    private boolean denied(Player player, Location from, Location to) {
        if (to == null || (Objects.equals(from.getWorld(), to.getWorld())
            && from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ())) return false;
        Integrations.Nation target = api.plotNation(to);
        if (target == null || !data().getBoolean(borderPath(target.id()))) return false;
        Integrations.Nation origin = api.plotNation(from);
        if (origin != null && origin.id().equals(target.id())) return false;
        UUID traveler = player.getUniqueId();
        Integrations.Nation home = api.nation(traveler);
        if (home != null && (target.id().equals(home.id()) || validVisa(traveler, home.id(), target.id()))) return false;
        player.sendActionBar(Component.text(target.name() + " への入国には有効なビザが必要です。"));
        return true;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    private static String word(List<String> words, int index) {
        require(index < words.size(), "必要な引数を指定してください。");
        return words.get(index);
    }
}
