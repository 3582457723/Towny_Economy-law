package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import static dev.townyeconomylaw.State.require;

final class GameIllness implements Listener {
    // Chance units are one per 100,000 Minecraft exposure rolls.
    enum Kind {
        COLD("風邪", "咳・微熱", 1, 15, 32, 5, 0, 0),
        INFLUENZA("インフルエンザ", "発熱・咳", 320, 8_730, 15_700, 5, 0, 1),
        PARASITE("エキノコックス", "発熱・最大体力低下", 0, 0, 0, 1, -1, -1),
        COVID("COVID-19", "吐き気・発熱・咳", 430, 21_000, 33_700, 7, 1, 1);

        private final String label;
        private final String symptoms;
        private final int warmAir;
        private final int coldAir;
        private final int contact;
        private final int doses;
        private final int slowness;
        private final int fatigue;

        Kind(String label, String symptoms, int warmAir, int coldAir, int contact, int doses,
             int slowness, int fatigue) {
            this.label = label;
            this.symptoms = symptoms;
            this.warmAir = warmAir;
            this.coldAir = coldAir;
            this.contact = contact;
            this.doses = doses;
            this.slowness = slowness;
            this.fatigue = fatigue;
        }

        String label() {
            return label;
        }

        private String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        private int airborne(GameCalendar.Season season) {
            return switch (season) {
                case SPRING, SUMMER -> warmAir;
                case AUTUMN, WINTER -> coldAir;
            };
        }

        private static Kind parse(String value) {
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(value) || kind.label.equalsIgnoreCase(value)) return kind;
            }
            throw new State.Rejected("症状は cold / influenza / parasite / covid から指定してください。");
        }
    }

    private record Pair(UUID first, UUID second) {
        static Pair sorted(UUID one, UUID other) {
            return one.compareTo(other) < 0 ? new Pair(one, other) : new Pair(other, one);
        }
    }

    private static final long PARASITE_DEADLINE_TICKS = 5L * 24_000;
    private static final double AIR_RADIUS = 4;
    private static final int SOURCES_PER_SWEEP = 64;
    private static final int TARGETS_PER_SOURCE = 32;
    private static final int MAX_CONTACTS = 4_096;
    private static final int EFFECT_TICKS = 80;
    private static final int COUGH_INTERVAL = 12;

    private final TownyEconomyLaw plugin;
    private final State state;
    private final GameCalendar calendar;
    private final GameHerbs herbs;
    private final Government government;
    private final AttributeModifier maxHealthPenalty;
    private final Map<Pair, Long> contactsUntil = new HashMap<>();
    private final int exposureInterval;
    private long seconds;
    private int sourceOffset;

    GameIllness(TownyEconomyLaw plugin, State state, GameCalendar calendar, GameHerbs herbs, Government government) {
        this.plugin = plugin;
        this.state = state;
        this.calendar = calendar;
        this.herbs = herbs;
        this.government = government;
        this.exposureInterval = Math.clamp(plugin.getConfig().getInt("game-illness.exposure-interval-seconds", 30), 5, 300);
        this.maxHealthPenalty = new AttributeModifier(new NamespacedKey(plugin, "game_parasite_health"), -4,
            AttributeModifier.Operation.ADD_NUMBER);
    }

    Set<Kind> active(Player player) {
        EnumSet<Kind> active = EnumSet.noneOf(Kind.class);
        for (Kind kind : Kind.values()) {
            if (state.data().contains(path(player, kind) + ".world")) active.add(kind);
        }
        return active;
    }

    private String path(Player player, Kind kind) {
        return "game-illness.players." + player.getUniqueId() + "." + kind.id();
    }

    void status(CommandSender sender, Player player) {
        Set<Kind> active = active(player);
        if (active.isEmpty()) {
            sender.sendMessage(player.getName() + " の病気はありません。");
            return;
        }

        for (Kind kind : active) {
            String entry = path(player, kind);
            if (kind == Kind.PARASITE) {
                sender.sendMessage(kind.label + " / " + kind.symptoms + " / ゲーム内の薬は1日1回");
            } else {
                sender.sendMessage(kind.label + " / " + kind.symptoms + " / 服用済み "
                    + state.data().getInt(entry + ".doses") + " / " + kind.doses + " 日");
            }
        }
    }

    void seed(Player target, String illness) {
        require(eligible(target), "サバイバルまたはアドベンチャーの生存中プレイヤーを指定してください。");
        Kind kind = Kind.parse(illness);
        require(!active(target).contains(kind), "対象はすでにこの病気にかかっています。");
        infect(Map.of(target, EnumSet.of(kind)));
    }

    void clear(Player target, String illness) {
        Kind kind = Kind.parse(illness);
        require(active(target).contains(kind), "対象はこの病気にかかっていません。");
        state.transaction(undo -> state.data().set(path(target, kind), null));
        if (kind == Kind.PARASITE) updateMaxHealth(target, false);
        target.sendMessage(kind.label + " のゲーム内効果が解除されました。");
    }

    void touchFox(Player player, Fox fox) {
        if (!eligible(player) || active(player).contains(Kind.PARASITE)
                || herbs.kind(player.getInventory().getItemInMainHand()) == GameHerbs.Kind.GLOVES
                || herbs.kind(player.getInventory().getItemInOffHand()) == GameHerbs.Kind.GLOVES
                || !reserveContact(player.getUniqueId(), fox.getUniqueId())) return;
        if (roll(45_700)) infect(Map.of(player, EnumSet.of(Kind.PARASITE)));
    }

    private boolean eligible(Player player) {
        GameMode mode = player.getGameMode();
        return player.isOnline() && !player.isDead() && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE);
    }

    void tick() {
        seconds++;
        boolean sweep = seconds % exposureInterval == 0;
        Map<Player, Set<Kind>> contagious = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<Kind> active = active(player);
            if (!eligible(player)) {
                updateMaxHealth(player, false);
                continue;
            }
            if (active.isEmpty()) continue;
            if (active.contains(Kind.PARASITE) && parasiteExpired(player)) {
                state.transaction(undo -> state.data().set(path(player, Kind.PARASITE), null));
                updateMaxHealth(player, false);
                player.sendMessage("ゲーム内のエキノコックスが発症してから5日が経過しました。");
                player.setHealth(0);
                continue;
            }
            applySymptoms(player, active);
            boolean respiratory = active.stream().anyMatch(kind -> kind.contact > 0);
            if (respiratory && Math.floorMod(seconds + player.getUniqueId().hashCode(), COUGH_INTERVAL) == 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BREATH, 0.45f, 0.8f);
            }
            if (sweep && respiratory) contagious.put(player, active);
        }
        if (sweep) {
            contactsUntil.entrySet().removeIf(entry -> entry.getValue() <= seconds);
            spreadAir(contagious);
        }
    }

    private boolean parasiteExpired(Player player) {
        String entry = path(player, Kind.PARASITE);
        World world = illnessWorld(entry);
        return world != null && calendar.elapsedTicks(world) - state.data().getLong(entry + ".started-ticks") >= PARASITE_DEADLINE_TICKS;
    }

    private void spreadAir(Map<Player, Set<Kind>> contagious) {
        List<Player> sources = new ArrayList<>(contagious.keySet());
        if (sources.isEmpty()) return;
        Map<Player, EnumSet<Kind>> exposed = new LinkedHashMap<>();
        int count = Math.min(sources.size(), SOURCES_PER_SWEEP);
        int start = Math.floorMod(sourceOffset, sources.size());
        for (int index = 0; index < count; index++) {
            Player source = sources.get((start + index) % sources.size());
            World world = source.getWorld();
            GameCalendar.Season season = calendar.season(world);
            var location = source.getLocation();
            List<Player> near = new ArrayList<>(world.getNearbyPlayers(location, AIR_RADIUS,
                player -> !player.equals(source) && eligible(player)));
            if (near.size() > TARGETS_PER_SOURCE) Collections.shuffle(near);
            for (int neighbor = 0; neighbor < Math.min(near.size(), TARGETS_PER_SOURCE); neighbor++) {
                Player target = near.get(neighbor);
                if (location.distanceSquared(target.getLocation()) > AIR_RADIUS * AIR_RADIUS || !source.hasLineOfSight(target)) continue;
                if (source.getBoundingBox().overlaps(target.getBoundingBox())) {
                    if (reserveContact(source.getUniqueId(), target.getUniqueId())) {
                        expose(exposed, contagious.get(source), target, null);
                        expose(exposed, active(target), source, null);
                    }
                } else {
                    expose(exposed, contagious.get(source), target, season);
                }
            }
        }
        sourceOffset = (start + count) % sources.size();
        infect(exposed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void touch(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND && event.getRightClicked() instanceof Player target) {
            contact(event.getPlayer(), target);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void melee(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player first && event.getEntity() instanceof Player second) {
            contact(first, second);
        }
    }

    private void contact(Player first, Player second) {
        if (!eligible(first) || !eligible(second) || first.equals(second)) return;
        Set<Kind> firstSet = active(first);
        Set<Kind> secondSet = active(second);
        if (firstSet.equals(secondSet) || !reserveContact(first.getUniqueId(), second.getUniqueId())) return;
        Map<Player, EnumSet<Kind>> exposed = new LinkedHashMap<>();
        expose(exposed, firstSet, second, null);
        expose(exposed, secondSet, first, null);
        infect(exposed);
    }

    private boolean reserveContact(UUID first, UUID second) {
        Pair pair = Pair.sorted(first, second);
        Long until = contactsUntil.get(pair);
        if ((until != null && until > seconds) || (until == null && contactsUntil.size() >= MAX_CONTACTS)) return false;
        contactsUntil.put(pair, seconds + exposureInterval);
        return true;
    }

    private void expose(Map<Player, EnumSet<Kind>> exposed, Set<Kind> source, Player target, GameCalendar.Season season) {
        Set<Kind> current = active(target);
        for (Kind kind : source) {
            Set<Kind> scheduled = exposed.get(target);
            if (current.contains(kind) || (scheduled != null && scheduled.contains(kind))) continue;
            if (roll(season == null ? kind.contact : kind.airborne(season))) {
                exposed.computeIfAbsent(target, ignored -> EnumSet.noneOf(Kind.class)).add(kind);
            }
        }
    }

    private boolean roll(int chance) {
        return chance > 0 && ThreadLocalRandom.current().nextInt(100_000) < chance;
    }

    private void infect(Map<Player, EnumSet<Kind>> exposed) {
        if (exposed.isEmpty()) return;
        state.transaction(undo -> exposed.forEach((player, kinds) -> {
            World world = player.getWorld();
            for (Kind kind : kinds) {
                String entry = path(player, kind);
                state.data().set(entry + ".started-ticks", calendar.elapsedTicks(world));
                state.data().set(entry + ".world", world.getUID().toString());
            }
        }));
        exposed.forEach((player, kinds) -> {
            for (Kind kind : kinds) player.sendMessage(kind.label + " にかかりました。 /tel illness status");
            applySymptoms(player, active(player));
        });
    }

    private void applySymptoms(Player player, Set<Kind> active) {
        int slowness = -1;
        int fatigue = -1;
        List<String> symptoms = new ArrayList<>();
        for (Kind kind : active) {
            slowness = Math.max(slowness, kind.slowness);
            fatigue = Math.max(fatigue, kind.fatigue);
            symptoms.add(kind.label + "（" + kind.symptoms + "）");
        }
        effect(player, PotionEffectType.SLOWNESS, slowness);
        effect(player, PotionEffectType.MINING_FATIGUE, fatigue);
        if (active.contains(Kind.COVID)) effect(player, PotionEffectType.NAUSEA, 0);
        updateMaxHealth(player, active.contains(Kind.PARASITE));
        player.sendActionBar(Component.text(String.join("  ", symptoms)));
    }

    private void effect(Player player, PotionEffectType type, int amplifier) {
        if (amplifier < 0) return;
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && (current.getAmplifier() > amplifier
                || current.getAmplifier() == amplifier && (current.isInfinite() || current.getDuration() > 40))) return;
        player.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, amplifier, true, false, true));
    }

    private void updateMaxHealth(Player player, boolean reduce) {
        var health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health == null) return;
        boolean applied = health.getModifier(maxHealthPenalty.getKey()) != null;
        if (applied == reduce) return;
        if (reduce) {
            health.addTransientModifier(maxHealthPenalty);
            if (player.getHealth() > health.getValue()) player.setHealth(health.getValue());
        } else {
            health.removeModifier(maxHealthPenalty.getKey());
        }
    }

    void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) updateMaxHealth(player, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void medicine(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        GameHerbs.Kind item = herbs.kind(event.getItem());
        Kind kind = medicineFor(item);
        if (kind == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (eligible(player) && !government.jailed(player) && player.hasPermission("townyeconomylaw.use")) {
            plugin.run(player, () -> dose(player, kind, item));
        }
    }

    private Kind medicineFor(GameHerbs.Kind item) {
        if (item == null) return null;
        return switch (item) {
            case COLD_MEDICINE -> Kind.COLD;
            case INFLUENZA_MEDICINE -> Kind.INFLUENZA;
            case PARASITE_MEDICINE -> Kind.PARASITE;
            case ANTIVIRAL -> Kind.COVID;
            default -> null;
        };
    }

    private void dose(Player player, Kind kind, GameHerbs.Kind medicine) {
        String entry = path(player, kind);
        require(state.data().contains(entry + ".world"), "この薬に対応するゲーム内の病気にかかっていません。");
        World world = illnessWorld(entry);
        require(world != null, "発症したワールドの暦を参照できません。");
        require(kind != Kind.PARASITE || !parasiteExpired(player), "ゲーム内の治療期限が過ぎています。");
        long day = calendar.day(world);
        require(day > state.data().getLong(entry + ".last-dose", Long.MIN_VALUE), "このゲーム内の日の薬は服用済みです。");

        int count = state.data().getInt(entry + ".doses") + 1;
        boolean cured = kind == Kind.PARASITE ? roll(50_000) : count >= kind.doses;
        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        require(herbs.kind(held) == medicine && held.getAmount() > 0, "対応する薬をメインハンドに持ってください。");
        ItemStack remaining = held.getAmount() == 1 ? null : held.asQuantity(held.getAmount() - 1);
        state.transaction(undo -> {
            undo.push(() -> inventory.setItemInMainHand(held));
            inventory.setItemInMainHand(remaining);
            if (cured) {
                state.data().set(entry, null);
            } else {
                state.data().set(entry + ".last-dose", day);
                state.data().set(entry + ".doses", count);
            }
        });

        if (cured) {
            if (kind == Kind.PARASITE) updateMaxHealth(player, false);
            player.sendMessage(kind.label + " が治りました。");
        } else if (kind == Kind.PARASITE) {
            player.sendMessage("薬を服用しました。次のゲーム内の日に再度使用できます。");
        } else {
            player.sendMessage(kind.label + " の薬を服用しました。 " + count + " / " + kind.doses + " 日");
        }
    }

    private World illnessWorld(String entry) {
        String id = state.data().getString(entry + ".world");
        return id == null ? null : Bukkit.getWorld(UUID.fromString(id));
    }
}
