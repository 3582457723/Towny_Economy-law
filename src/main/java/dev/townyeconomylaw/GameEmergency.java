package dev.townyeconomylaw;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import static dev.townyeconomylaw.State.require;

final class GameEmergency implements Listener {
    private record Jurisdiction(boolean national, UUID id, String name) {
        String path() {
            return "emergencies." + (national ? "nation." : "town.") + id;
        }
    }

    private enum Emergency {
        CRISIS("危機", false),
        ILLNESS("疾病注意報", false),
        PANDEMIC("感染症緊急事態", true),
        MARTIAL_LAW("戒厳令", true);

        final String display;
        final boolean lockdown;

        Emergency(String display, boolean lockdown) {
            this.display = display;
            this.lockdown = lockdown;
        }
    }

    private final State state;
    private final Integrations api;
    private final Government government;
    private final GameIllness illness;
    private final Set<UUID> lockedNations = new HashSet<>();
    private final Set<UUID> lockedTowns = new HashSet<>();

    GameEmergency(State state, Integrations api, Government government, GameIllness illness) {
        this.state = state;
        this.api = api;
        this.government = government;
        this.illness = illness;
        for (String id : state.keys("emergencies.nation")) refresh(new Jurisdiction(true, UUID.fromString(id), ""));
        for (String id : state.keys("emergencies.town")) refresh(new Jurisdiction(false, UUID.fromString(id), ""));
    }

    void command(Player actor, boolean national, List<String> args) {
        String operation = word(args, 1);
        String action = word(args, 2);
        String scope = national ? "nation" : "town";
        String path = "/tel " + scope + " " + operation;
        Jurisdiction jurisdiction = jurisdiction(actor, national, !action.equals("status"));
        if (action.equals("status")) {
            require(args.size() == 3, "使い方 " + path + " status");
            status(actor, jurisdiction);
            return;
        }

        if (operation.equals("lockdown")) {
            require(args.size() == 3 && (action.equals("on") || action.equals("off")),
                "使い方 " + path + " <on|off|status>");
            setLockdown(actor, jurisdiction, action.equals("on"));
            return;
        }
        require(operation.equals("emergency"), "使い方 /tel " + scope + " <lockdown|emergency>");
        if (action.equals("clear")) {
            require(args.size() == 3, "使い方 " + path + " clear");
            clearEmergency(actor, jurisdiction);
            return;
        }
        require(action.equals("declare"), "使い方 " + path + " <declare|clear|status>");
        Emergency type = switch (word(args, 3)) {
            case "crisis", "危機" -> Emergency.CRISIS;
            case "illness", "病気" -> Emergency.ILLNESS;
            case "pandemic", "パンデミック" -> Emergency.PANDEMIC;
            case "martial_law", "戒厳令" -> Emergency.MARTIAL_LAW;
            default -> throw new State.Rejected("緊急事態の種類は crisis、illness、pandemic、martial_law のいずれかです。");
        };
        require(type != Emergency.MARTIAL_LAW || national, "戒厳令は国のみ宣言できます。");
        String notice = String.join(" ", args.subList(4, args.size())).strip();
        require(notice.length() <= 200, "発表文は200文字以内で指定してください。");
        declareEmergency(actor, jurisdiction, type, notice);
    }

    private Jurisdiction jurisdiction(Player actor, boolean national, boolean modify) {
        UUID id = actor.getUniqueId();
        if (national) {
            Integrations.Nation nation = government.citizenship(actor);
            require(!modify || id.equals(nation.leader()) || government.hasRole(nation, id, Government.Role.MINISTER),
                "国のリーダーまたは大臣のみ設定できます。");
            return new Jurisdiction(true, nation.id(), nation.name());
        }

        Integrations.Town town = api.town(id);
        require(town != null, "Towny の町に所属している必要があります。");
        require(!modify || id.equals(town.mayor()), "町長のみ設定できます。");
        return new Jurisdiction(false, town.id(), town.name());
    }

    private void setLockdown(Player actor, Jurisdiction jurisdiction, boolean enabled) {
        String path = jurisdiction.path();
        state.transaction(undo -> {
            data().set(path + ".lockdown", enabled ? true : null);
            if (!enabled) data().set(path + ".emergency.lockdown", null);
        });
        refresh(jurisdiction);
        announce(actor, jurisdiction, enabled ? "ロックダウンを開始しました。" : "ロックダウンを解除しました。");
    }

    private void declareEmergency(Player actor, Jurisdiction jurisdiction, Emergency type, String notice) {
        String emergency = jurisdiction.path() + ".emergency";
        state.transaction(undo -> {
            data().set(emergency + ".type", type.name());
            data().set(emergency + ".notice", notice.isEmpty() ? null : notice);
            data().set(emergency + ".lockdown", type.lockdown);
        });
        refresh(jurisdiction);
        announce(actor, jurisdiction, type.display + "を発令しました。"
            + (type.lockdown ? " ロックダウン中です。" : "") + (notice.isEmpty() ? "" : " " + notice));
    }

    private void clearEmergency(Player actor, Jurisdiction jurisdiction) {
        String emergency = jurisdiction.path() + ".emergency";
        require(data().contains(emergency + ".type"), "発令中の緊急事態はありません。");
        state.transaction(undo -> data().set(emergency, null));
        refresh(jurisdiction);
        announce(actor, jurisdiction, "緊急事態を解除しました。"
            + (locked(jurisdiction) ? " 手動設定のロックダウンは継続します。" : ""));
    }

    private void status(Player actor, Jurisdiction jurisdiction) {
        String emergency = jurisdiction.path() + ".emergency";
        String type = data().getString(emergency + ".type");
        actor.sendMessage("[" + jurisdiction.name() + "] " + (type == null ? "発令中の緊急事態なし" : Emergency.valueOf(type).display)
            + " / ロックダウン " + (locked(jurisdiction) ? "on" : "off"));
        String notice = data().getString(emergency + ".notice");
        if (notice != null) actor.sendMessage(notice);
        var cases = new EnumMap<GameIllness.Kind, Integer>(GameIllness.Kind.class);
        for (Player resident : Bukkit.getOnlinePlayers()) {
            if (belongs(resident, jurisdiction)) {
                for (GameIllness.Kind kind : illness.active(resident)) cases.merge(kind, 1, Integer::sum);
            }
        }
        if (!cases.isEmpty()) actor.sendMessage("オンライン住民の症例 / " + String.join("、", cases.entrySet().stream()
            .map(entry -> entry.getKey().label() + " " + entry.getValue() + "人").toList()));
    }

    private void announce(Player actor, Jurisdiction jurisdiction, String announcement) {
        String message = "[" + jurisdiction.name() + "] " + announcement;
        actor.sendMessage(message);
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!recipient.getUniqueId().equals(actor.getUniqueId()) && belongs(recipient, jurisdiction)) {
                recipient.sendMessage(message);
            }
        }
    }

    private boolean belongs(Player player, Jurisdiction jurisdiction) {
        if (jurisdiction.national()) {
            Integrations.Nation home = api.nation(player.getUniqueId());
            return home != null && home.id().equals(jurisdiction.id());
        }
        Integrations.Town home = api.town(player.getUniqueId());
        return home != null && home.id().equals(jurisdiction.id());
    }

    private void refresh(Jurisdiction jurisdiction) {
        Set<UUID> active = jurisdiction.national() ? lockedNations : lockedTowns;
        if (data().getBoolean(jurisdiction.path() + ".lockdown") || data().getBoolean(jurisdiction.path() + ".emergency.lockdown")) {
            active.add(jurisdiction.id());
        } else {
            active.remove(jurisdiction.id());
        }
    }

    private boolean locked(Jurisdiction jurisdiction) {
        return (jurisdiction.national() ? lockedNations : lockedTowns).contains(jurisdiction.id());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        restrict(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        restrict(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(PlayerPortalEvent event) {
        restrict(event);
    }

    private void restrict(PlayerMoveEvent event) {
        if (lockedNations.isEmpty() && lockedTowns.isEmpty()) return;
        Player visitor = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || visitor.hasPermission("townyeconomylaw.lockdown.bypass")
            || (Objects.equals(from.getWorld(), to.getWorld()) && from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ())) return;
        if (event instanceof PlayerTeleportEvent teleport && teleport.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
            && government.jailed(visitor) && to.equals(government.anchor(visitor))) return;
        Integrations.Town destination = townAt(to);
        if (destination == null) return;
        Integrations.Nation nation = destination.nation();
        boolean nationLocked = nation != null && lockedNations.contains(nation.id());
        boolean townLocked = lockedTowns.contains(destination.id());
        if (!nationLocked && !townLocked) return;

        Integrations.Town origin = townAt(from);
        String blocked = denied(visitor, origin, destination, nationLocked, townLocked);
        if (blocked != null) {
            event.setCancelled(true);
            visitor.sendActionBar(Component.text(blocked + " はロックダウン中です。"));
        }
    }

    private String denied(Player visitor, Integrations.Town origin, Integrations.Town destination,
                          boolean nationLocked, boolean townLocked) {
        Integrations.Town home = api.town(visitor.getUniqueId());
        if (townLocked && !sameTown(origin, destination) && !sameTown(home, destination)) return destination.name();
        if (nationLocked && !sameNation(origin, destination.nation()) && !sameNation(home, destination.nation())) {
            return destination.nation().name();
        }
        return null;
    }

    private Integrations.Town townAt(Location location) {
        Integrations.PlotAccess plot = api.plotAccess(location);
        return plot == null ? null : api.townById(plot.town());
    }

    private static boolean sameTown(Integrations.Town town, Integrations.Town other) {
        return town != null && town.id().equals(other.id());
    }

    private static boolean sameNation(Integrations.Town town, Integrations.Nation nation) {
        return town != null && town.nation() != null && town.nation().id().equals(nation.id());
    }

    private YamlConfiguration data() {
        return state.data();
    }

    private static String word(List<String> args, int position) {
        require(position < args.size(), "必要な引数を指定してください。");
        return args.get(position).toLowerCase(Locale.ROOT);
    }
}
