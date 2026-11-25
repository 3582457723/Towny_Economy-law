package dev.townyeconomylaw;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static dev.townyeconomylaw.State.require;

final class Enterprises {
    enum Kind { COMPANY, CORPORATION }
    record Firm(String id, String name, String code, Kind kind, UUID leader) {}

    private final State state;
    private final Integrations api;

    Enterprises(State state, Integrations api) {
        this.state = state;
        this.api = api;
    }

    private YamlConfiguration data() {
        return state.data();
    }

    static String path(Firm firm) {
        return "enterprises." + firm.id();
    }

    Firm get(String id) {
        String root = "enterprises." + id;
        String code = data().getString(root + ".code");
        return code == null ? null : new Firm(id, data().getString(root + ".name"), code,
            Kind.valueOf(data().getString(root + ".kind")), UUID.fromString(data().getString(root + ".leader")));
    }

    Firm byCode(String code) {
        String id = data().getString("enterprise-codes." + code.toUpperCase(Locale.ROOT));
        Firm firm = id == null ? null : get(id);
        require(firm != null, "指定コードの企業・法人が見つかりません。");
        return firm;
    }

    Firm byCode(String code, Kind kind) {
        Firm firm = byCode(code);
        require(firm.kind() == kind, "コマンドと組織の種類が一致しません。");
        return firm;
    }

    void create(Player player, Kind kind, String name, String code) {
        verifyCode(code, null);
        Firm firm = new Firm(UUID.randomUUID().toString(), name, code, kind, player.getUniqueId());
        state.transaction(undo -> {
            data().set(path(firm) + ".name", name);
            data().set(path(firm) + ".kind", kind.name());
            data().set(path(firm) + ".leader", firm.leader().toString());
            data().set(path(firm) + ".members." + firm.leader(), true);
            setCode(firm, code);
            setActive(player, firm);
        });
        player.sendMessage(name + " [" + code + "] を作成しました。");
    }

    void edit(Player player, Firm firm, String name, String code) {
        leader(player, firm);
        verifyCode(code, firm.id());
        state.transaction(undo -> {
            data().set("enterprise-codes." + firm.code(), null);
            data().set(path(firm) + ".name", name);
            setCode(firm, code);
        });
        player.sendMessage("組織情報を更新しました。 " + code);
    }

    private void verifyCode(String code, String id) {
        require(code.matches("[A-Z]{4}"), "コードは大文字の英字4文字です。");
        String used = data().getString("enterprise-codes." + code);
        require(used == null || used.equals(id), "そのコードは使用されています。");
    }

    private void setCode(Firm firm, String code) {
        data().set(path(firm) + ".code", code);
        data().set("enterprise-codes." + code, firm.id());
    }

    void remove(Player player, Firm firm, String name) {
        leader(player, firm);
        require(firm.name().equals(name), "登録名と企業コードを入力してください。");
        require(state.keys(path(firm) + ".plots").isEmpty(), "所属プロットをすべて解除してください。");
        require(data().getLong(path(firm) + ".revenue") == 0, "売上を受け取ってから解散してください。");
        require(state.keys("transit-tickets").stream().noneMatch(id -> firm.id().equals(data().getString("transit-tickets." + id + ".firm"))),
            "未使用の切符がある組織は解散できません。");
        require(state.keys("transit-cards").stream().noneMatch(id -> firm.id().equals(data().getString("transit-cards." + id + ".journey.firm"))),
            "未精算のIC乗車がある組織は解散できません。");
        EnterpriseLoans.requireClear(state, firm);
        state.transaction(undo -> {
            data().set(path(firm), null);
            data().set("enterprise-codes." + firm.code(), null);
        });
        player.sendMessage("組織を解散しました。");
    }

    void invite(Player player, Firm firm, UUID target) {
        leader(player, firm);
        require(!member(firm, target), "すでにメンバーです。");
        state.transaction(undo -> data().set(path(firm) + ".invites." + target, true));
        player.sendMessage("プレイヤーを招待しました。");
    }

    void join(Player player, Firm firm) {
        UUID member = player.getUniqueId();
        require(data().getBoolean(path(firm) + ".invites." + member), "この組織からの招待がありません。");
        state.transaction(undo -> {
            data().set(path(firm) + ".invites." + member, null);
            data().set(path(firm) + ".members." + member, true);
            setActive(player, firm);
        });
        player.sendMessage(firm.name() + " に参加しました。");
    }

    void leave(Player player, Firm firm) {
        UUID id = player.getUniqueId();
        require(member(firm, id), "この組織に所属していません。");
        require(!firm.leader().equals(id), "代表権を移譲してから脱退してください。");
        state.transaction(undo -> {
            data().set(path(firm) + ".members." + id, null);
            if (firm.id().equals(data().getString(activePath(player, firm.kind())))) {
                data().set(activePath(player, firm.kind()), null);
            }
        });
        player.sendMessage(firm.name() + " を脱退しました。");
    }

    void transfer(Player player, Firm firm, UUID target) {
        leader(player, firm);
        require(member(firm, target), "移譲先はこの組織のメンバーである必要があります。");
        state.transaction(undo -> data().set(path(firm) + ".leader", target.toString()));
        player.sendMessage("代表権を移譲しました。");
    }

    void use(Player player, Firm firm) {
        require(member(firm, player.getUniqueId()), "この組織に所属していません。");
        state.transaction(undo -> setActive(player, firm));
        player.sendMessage("操作対象 " + firm.name() + " [" + firm.code() + "]");
    }

    Firm active(Player player, Kind kind) {
        String id = data().getString(activePath(player, kind));
        Firm selected = id == null ? null : get(id);
        if (selected != null && member(selected, player.getUniqueId())) return selected;
        List<Firm> list = memberships(player, kind);
        require(list.size() == 1, "操作対象を /" + kind.name().toLowerCase(Locale.ROOT) + " use <コード> で選択してください。");
        return list.getFirst();
    }

    List<Firm> memberships(Player player, Kind kind) {
        return state.keys("enterprises").stream().map(this::get)
            .filter(firm -> firm.kind() == kind && member(firm, player.getUniqueId())).toList();
    }

    boolean member(Firm firm, UUID id) {
        return data().getBoolean(path(firm) + ".members." + id);
    }

    void leader(Player player, Firm firm) {
        require(firm.leader().equals(player.getUniqueId()), "組織の代表者のみ実行できます。");
    }

    private String activePath(Player player, Kind kind) {
        return "enterprise-selection." + player.getUniqueId() + "." + kind.name();
    }

    private void setActive(Player player, Firm firm) {
        data().set(activePath(player, firm.kind()), firm.id());
    }

    void collect(Player player, Firm firm) {
        leader(player, firm);
        String revenue = path(firm) + ".revenue";
        long amount = data().getLong(revenue);
        require(amount > 0, "受け取れる運賃売上がありません。");
        state.transaction(undo -> {
            api.economy(undo, player.getUniqueId(), amount, true);
            data().set(revenue, null);
        });
        player.sendMessage("運賃売上を受け取りました。 " + Input.money(amount));
    }
}
