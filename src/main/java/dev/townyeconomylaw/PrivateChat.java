package dev.townyeconomylaw;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import static dev.townyeconomylaw.State.require;

final class PrivateChat implements Listener {
    private enum Channel {
        PARTY("政党"), SPY("諜報"), ALLIANCE("同盟"), LEGISLATURE("議会"), TERRITORY("領域代表");
        final String label;

        Channel(String label) {
            this.label = label;
        }
    }

    private final TownyEconomyLaw plugin;
    private final State state;
    private final Integrations api;
    private final Government government;
    private final Civics civics;
    private final Alliances alliances;
    private final Spies spies;
    private final Map<UUID, Channel> selected = new ConcurrentHashMap<>();

    PrivateChat(TownyEconomyLaw plugin, State state, Integrations api, Government government, Civics civics,
                Alliances alliances, Spies spies) {
        this.plugin = plugin;
        this.state = state;
        this.api = api;
        this.government = government;
        this.civics = civics;
        this.alliances = alliances;
        this.spies = spies;
    }

    void command(Player player, String[] args) {
        String name = Input.value(args, 1).toLowerCase(Locale.ROOT);
        if (name.equals("public") || name.equals("off")) {
            selected.remove(player.getUniqueId());
            player.sendMessage("通常チャットに切り替えました。");
            return;
        }
        Channel channel = switch (name) {
            case "potilical_party", "political_party" -> Channel.PARTY;
            case "spy" -> Channel.SPY;
            case "alliance" -> Channel.ALLIANCE;
            case "legislature" -> Channel.LEGISLATURE;
            case "teritory_readers", "territory_readers", "territory_leaders" -> Channel.TERRITORY;
            default -> throw new State.Rejected("使い方 /tel chat <political_party|spy|alliance|legislature|teritory_readers|public> [本文]");
        };
        if (args.length > 2) send(player, channel, Component.text(Input.text(args, 2, 400)));
        else {
            require(group(player, channel) != null, "このチャンネルを利用する資格がありません。");
            selected.put(player.getUniqueId(), channel);
            player.sendMessage(channel.label + " チャットに切り替えました。終了は /tel chat public");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        Channel channel = selected.get(sender.getUniqueId());
        if (channel == null) return;
        event.setCancelled(true);
        Component message = event.message();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sender.isOnline()) plugin.run(sender, () -> send(sender, channel, message));
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        selected.remove(event.getPlayer().getUniqueId());
    }

    private void send(Player sender, Channel channel, Component content) {
        require(sender.hasPermission("townyeconomylaw.use") && !government.jailed(sender), "このチャンネルを利用できません。");
        String group = group(sender, channel);
        require(group != null, "このチャンネルを利用する資格がありません。終了は /tel chat public");
        Component message = Component.text("[" + channel.label + "] ", NamedTextColor.AQUA)
            .append(Component.text(sender.getName() + " » ", NamedTextColor.GRAY)).append(content);
        for (Player receiver : Bukkit.getOnlinePlayers()) {
            if (!receiver.hasPermission("townyeconomylaw.use")) continue;
            boolean receives = channel == Channel.SPY
                ? spies.receives(receiver.getUniqueId(), api.nation(receiver.getUniqueId()), group)
                : group.equals(group(receiver, channel));
            if (receives) receiver.sendMessage(message);
        }
    }

    private String group(Player player, Channel channel) {
        UUID person = player.getUniqueId();
        Integrations.Nation nation = api.nation(person);
        if (channel == Channel.SPY) {
            UUID employer = spies.channel(person, nation);
            return employer == null ? null : employer.toString();
        }
        if (nation == null) return null;
        return switch (channel) {
            case PARTY -> {
                String party = civics.memberParty(nation, person);
                yield party == null ? null : nation.id() + ":" + party;
            }
            case ALLIANCE -> alliances.allianceName(nation.id());
            case LEGISLATURE -> person.equals(nation.leader()) || civics.politician(nation, person) ? nation.id().toString() : null;
            case TERRITORY -> isRepresentative(person, nation) ? territoryRoot(nation.id()).toString() : null;
            case SPY -> throw new IllegalStateException("Spy group is handled separately");
        };
    }

    private boolean isRepresentative(UUID person, Integrations.Nation nation) {
        if (person.equals(nation.leader())) return true;
        Integrations.Town town = api.town(person);
        return town != null && person.equals(town.mayor())
            && nation.id().toString().equals(state.data().getString("local-governments." + town.id() + ".parent"));
    }

    private UUID territoryRoot(UUID nation) {
        HashSet<UUID> visited = new HashSet<>();
        UUID current = nation;
        while (visited.add(current)) {
            String parent = state.data().getString("puppets." + current + ".parent");
            if (parent == null) return current;
            UUID id = UUID.fromString(parent);
            if (api.nationById(id) == null) return current;
            current = id;
        }
        return nation;
    }
}
