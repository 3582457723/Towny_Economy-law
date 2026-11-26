package dev.townyeconomylaw;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import static dev.townyeconomylaw.State.require;

final class GameFoxes implements Listener {
    private static final int PROTECTION_DAYS = 360;
    private static final int FEED_AFFECTION = 15;
    private static final int PET_AFFECTION = 35;
    private static final int HIT_AFFECTION = 45;
    private static final int HIGH_AFFECTION = 300;
    private static final int SOCIAL_RADIUS = 24;
    private static final int DEFENSE_RADIUS = 16;

    private record Intent(UUID target, long until, boolean rivalry) {}

    private final TownyEconomyLaw plugin;
    private final Government government;
    private final GameHerbs herbs;
    private final GameIllness illness;
    private final GameCalendar calendar;
    private final NamespacedKey ownerKey;
    private final NamespacedKey rankKey;
    private final NamespacedKey protectedDaysKey;
    private final NamespacedKey preferredClockKey;
    private final NamespacedKey sampledWorldKey;
    private final NamespacedKey sampledDayKey;
    private final Map<UUID, Set<UUID>> pets = new HashMap<>();
    private final Map<UUID, Intent> intents = new HashMap<>();
    private final Map<UUID, Integer> sparHits = new HashMap<>();
    private final Set<UUID> bedSleep = new HashSet<>();
    private final Set<UUID> sprintInputs = new HashSet<>();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private long seconds;

    GameFoxes(TownyEconomyLaw plugin, Government government, GameHerbs herbs, GameIllness illness, GameCalendar calendar) {
        this.plugin = plugin;
        this.government = government;
        this.herbs = herbs;
        this.illness = illness;
        this.calendar = calendar;
        ownerKey = new NamespacedKey(plugin, "fox_owner");
        rankKey = new NamespacedKey(plugin, "fox_rank");
        protectedDaysKey = new NamespacedKey(plugin, "fox_protected_days");
        preferredClockKey = new NamespacedKey(plugin, "fox_preferred_clock");
        sampledWorldKey = new NamespacedKey(plugin, "fox_sampled_world");
        sampledDayKey = new NamespacedKey(plugin, "fox_sampled_day");
    }

    void loadExisting() {
        for (World world : Bukkit.getWorlds()) {
            for (Fox fox : world.getEntitiesByClass(Fox.class)) track(fox);
        }
    }

    @EventHandler
    public void load(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Fox fox) track(fox);
        }
    }

    @EventHandler
    public void unload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Fox fox) untrack(fox);
        }
    }

    @EventHandler
    public void death(EntityDeathEvent event) {
        if (event.getEntity() instanceof Fox fox) untrack(fox);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Fox fox) || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!canUse(player)) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        GameHerbs.Kind kind = herbs.kind(hand);
        boolean food = hand.getType() == Material.SWEET_BERRIES || hand.getType() == Material.COOKED_CHICKEN;
        UUID owner = owner(fox);
        if (kind == GameHerbs.Kind.PARASITE_SYRINGE) {
            event.setCancelled(true);
            plugin.run(player, () -> vaccinate(player, fox));
            return;
        }
        if (owner == null) {
            if (!gloved(player) && immunity(fox) == 0) illness.touchFox(player, fox);
            if (food) {
                event.setCancelled(true);
                feed(player, fox, true);
            }
            return;
        }
        boolean owns = owner.equals(player.getUniqueId());
        if (food || hand.getType() == Material.NAME_TAG || (owns && (player.isSneaking() || sprintHeld(player)))) {
            event.setCancelled(true);
        }
        if (!owns) return;
        if (sprintHeld(player) && fox.customName() != null) {
            plugin.run(player, () -> recall(player, fox));
        } else if (player.isSneaking()) {
            int affection = adjust(fox, owner, PET_AFFECTION);
            heart(fox);
            player.sendMessage("キツネをなでました。 好感度 " + displayAffection(affection));
        } else if (hand.getType() == Material.NAME_TAG) {
            plugin.run(player, () -> name(player, fox, hand));
        } else if (food) {
            feed(player, fox, false);
        }
    }

    @EventHandler
    public void sprintInput(PlayerInputEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        if (event.getInput().isSprint()) sprintInputs.add(player);
        else sprintInputs.remove(player);
    }

    @EventHandler
    public void forgetInput(PlayerQuitEvent event) {
        sprintInputs.remove(event.getPlayer().getUniqueId());
    }

    private boolean sprintHeld(Player player) {
        return player.isSprinting() || sprintInputs.contains(player.getUniqueId());
    }

    @EventHandler
    public void recallByNameTag(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() != EquipmentSlot.HAND || !sprintHeld(player)
            || item == null || item.getType() != Material.NAME_TAG || !item.hasItemMeta() || !canUse(player)) return;
        Component name = item.getItemMeta().customName();
        if (name == null) return;
        event.setCancelled(true);
        plugin.run(player, () -> call(player, plain(name)));
    }

    private void feed(Player player, Fox fox, boolean wild) {
        consume(player);
        int affection = adjust(fox, player.getUniqueId(), FEED_AFFECTION);
        if (wild && random.nextDouble() < .25) {
            tame(player, fox);
            player.sendMessage("キツネが仲間になりました。 好感度 " + displayAffection(affection));
        } else {
            player.sendMessage("キツネに餌を与えました。 好感度 " + displayAffection(affection));
        }
        heart(fox);
    }

    private void tame(Player player, Fox fox) {
        var data = fox.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(rankKey, PersistentDataType.INTEGER, 1);
        fox.setFirstTrustedPlayer(player);
        fox.setPersistent(true);
        fox.setRemoveWhenFarAway(false);
        fox.setCanPickupItems(false);
        var health = fox.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(18.0);
            fox.setHealth(health.getValue());
        }
        fox.setTarget(null);
        track(fox);
    }

    private void name(Player player, Fox fox, ItemStack tag) {
        Component name = tag.hasItemMeta() ? tag.getItemMeta().customName() : null;
        require(name != null && !plain(name).isBlank(), "金床で名前を付けた名札を使ってください。");
        fox.customName(name);
        fox.setCustomNameVisible(true);
        consume(player);
        player.sendMessage("キツネに名前を付けました。 " + plain(name));
    }

    private void vaccinate(Player player, Fox fox) {
        UUID owner = owner(fox);
        require(owner == null || owner.equals(player.getUniqueId()), "飼い主だけがペットのキツネに薬を使えます。");
        World clockWorld = fox.getWorld();
        long day = calendar.day(clockWorld);
        consume(player);
        var data = fox.getPersistentDataContainer();
        data.set(protectedDaysKey, PersistentDataType.INTEGER, PROTECTION_DAYS);
        data.set(preferredClockKey, PersistentDataType.STRING, clockWorld.getUID().toString());
        data.set(sampledWorldKey, PersistentDataType.STRING, clockWorld.getUID().toString());
        data.set(sampledDayKey, PersistentDataType.LONG, day);
        heart(fox);
        player.sendMessage("キツネの寄生生物を除去し、" + PROTECTION_DAYS + "ゲーム日分の免疫を付与しました。");
    }

    private int immunity(Fox fox) {
        var data = fox.getPersistentDataContainer();
        int remaining = data.getOrDefault(protectedDaysKey, PersistentDataType.INTEGER, 0);
        if (remaining <= 0) return 0;
        String preferred = data.get(preferredClockKey, PersistentDataType.STRING);
        World clockWorld = preferred == null ? null : Bukkit.getWorld(UUID.fromString(preferred));
        if (clockWorld == null) clockWorld = fox.getWorld();
        long day = calendar.day(clockWorld);
        String id = clockWorld.getUID().toString();
        String previousWorld = data.get(sampledWorldKey, PersistentDataType.STRING);
        long previousDay = data.getOrDefault(sampledDayKey, PersistentDataType.LONG, day);
        if (id.equals(previousWorld) && day <= previousDay) return remaining;
        if (id.equals(previousWorld)) remaining -= (int) Math.min(remaining, day - previousDay);
        data.set(protectedDaysKey, PersistentDataType.INTEGER, remaining);
        data.set(sampledWorldKey, PersistentDataType.STRING, id);
        data.set(sampledDayKey, PersistentDataType.LONG, day);
        return remaining;
    }

    void call(Player player, String name) {
        String selector = name.strip();
        Fox closest = null;
        double nearest = 64 * 64;
        for (Fox fox : owned(player.getUniqueId())) {
            Component customName = fox.customName();
            if (customName == null || (!selector.isEmpty() && !selector.equalsIgnoreCase(plain(customName)))
                || !fox.getWorld().equals(player.getWorld())) continue;
            double distance = fox.getLocation().distanceSquared(player.getLocation());
            if (distance <= nearest) {
                nearest = distance;
                closest = fox;
            }
        }
        require(closest != null, "64ブロック以内の読み込み済みで、名付けた自分のキツネが見つかりません。");
        recall(player, closest);
    }

    private void recall(Player player, Fox fox) {
        wake(fox);
        fox.setSitting(false);
        clearIntent(fox);
        boolean moved = fox.getLocation().distanceSquared(player.getLocation()) <= 4
            || fox.getPathfinder().moveTo(player, 1.5);
        if (!moved) moved = findSafeSpot(player, fox);
        require(moved, "キツネが近くへ移動できる経路がありません。");
        heart(fox);
        player.sendMessage("キツネを呼びました。 " + plain(fox.customName()));
    }

    private boolean findSafeSpot(Player player, Fox fox) {
        Location location = player.getLocation();
        World world = player.getWorld();
        for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Location spot = location.clone().add(offset[0], 0, offset[1]);
            int x = spot.getBlockX();
            int y = spot.getBlockY();
            int z = spot.getBlockZ();
            if (y <= world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            var floor = world.getBlockAt(x, y - 1, z);
            var space = world.getBlockAt(x, y, z);
            if (floor.getType().isSolid() && !floor.isLiquid() && !space.isLiquid()
                && space.getType().isAir() && fox.teleport(spot)) return true;
        }
        return false;
    }

    void info(Player player) {
        Entity target = player.getTargetEntity(5);
        require(target instanceof Fox fox && player.getUniqueId().equals(owner(fox)),
            "自分のキツネに照準を合わせてください。");
        Fox fox = (Fox) target;
        UUID owner = player.getUniqueId();
        Fox leader = leader(owned(owner).stream().filter(pet -> pet.getWorld().equals(player.getWorld())
            && pet.getLocation().distanceSquared(player.getLocation()) <= SOCIAL_RADIUS * SOCIAL_RADIUS).toList());
        int days = immunity(fox);
        player.sendMessage("キツネ " + (fox.customName() == null ? "未命名" : plain(fox.customName()))
            + " / 飼い主 " + player.getName() + " / 好感度 " + displayAffection(affection(fox, owner)));
        player.sendMessage("社会順位 " + rank(fox) + (fox.equals(leader) ? "（群れのリーダー）" : "")
            + " / 寄生生物に対する免疫 " + (days == 0 ? "なし" : "残り " + days + "ゲーム日"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent event) {
        if (event.getEntity() instanceof Fox fox && owner(fox) != null && event.getTarget() != null
            && !allowed(fox, event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventHunting(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Fox fox) || owner(fox) == null) return;
        if (!allowed(fox, event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        Intent intent = intents.get(fox.getUniqueId());
        if (intent.rivalry() && event.getEntity() instanceof Fox rival) {
            // Sparring shows the animals fighting without killing pets.
            event.setCancelled(true);
            sparHits.merge(fox.getUniqueId(), 1, Integer::sum);
            rival.playHurtAnimation(0);
            rival.getWorld().playSound(rival.getLocation(), Sound.ENTITY_FOX_HURT, .65f, 1.0f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void defendOwner(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0) return;
        Entity actor = event.getDamager();
        if (actor instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) actor = shooter;
        if (actor instanceof Player player && event.getEntity() instanceof Fox fox) {
            adjust(fox, player.getUniqueId(), -HIT_AFFECTION);
            if (event.getDamager().equals(player) && player.getInventory().getItemInMainHand().getType().isAir()
                && owner(fox) == null && !gloved(player) && immunity(fox) == 0) illness.touchFox(player, fox);
        }
        if (actor instanceof Player player && event.getEntity() instanceof Mob target) react(player, target);
        else if (event.getEntity() instanceof Player player && actor instanceof Mob attacker) react(player, attacker);
    }

    private boolean allowed(Fox fox, Entity target) {
        Intent intent = intents.get(fox.getUniqueId());
        return intent != null && seconds < intent.until() && intent.target().equals(target.getUniqueId());
    }

    private void react(Player player, Mob target) {
        if (!canUse(player) || player.isSleeping() || (target instanceof Fox fox && player.getUniqueId().equals(owner(fox)))) return;
        for (Fox fox : owned(player.getUniqueId())) {
            if (!fox.getWorld().equals(target.getWorld()) || fox.getLocation().distanceSquared(target.getLocation()) > DEFENSE_RADIUS * DEFENSE_RADIUS) continue;
            wake(fox);
            fox.setSitting(false);
            engage(fox, target, false);
        }
    }

    private void engage(Fox fox, Mob target, boolean rivalry) {
        if (!rivalry) clearIntent(fox);
        intents.put(fox.getUniqueId(), new Intent(target.getUniqueId(), seconds + (rivalry ? 4 : 9), rivalry));
        fox.setTarget(target);
    }

    private void clearIntent(Fox fox) {
        UUID id = fox.getUniqueId();
        Intent intent = intents.remove(id);
        if (fox.getTarget() != null) fox.setTarget(null);
        if (intent == null || !intent.rivalry()) return;
        int hits = sparHits.getOrDefault(id, 0);
        sparHits.remove(id);
        Intent otherIntent = intents.get(intent.target());
        if (otherIntent == null || !otherIntent.rivalry() || !id.equals(otherIntent.target())) return;
        intents.remove(intent.target());
        int otherHits = sparHits.getOrDefault(intent.target(), 0);
        sparHits.remove(intent.target());
        if (!(Bukkit.getEntity(intent.target()) instanceof Fox other) || !other.isValid()) return;
        if (other.getTarget() != null) other.setTarget(null);
        if (seconds >= intent.until() && hits != otherHits) {
            Fox winner = hits > otherHits ? fox : other;
            promote(winner, Math.max(rank(fox), rank(other)));
        }
    }

    private void promote(Fox fox, int currentHighest) {
        if (currentHighest < Integer.MAX_VALUE) {
            fox.getPersistentDataContainer().set(rankKey, PersistentDataType.INTEGER, currentHighest + 1);
        }
    }

    void tick() {
        seconds++;
        boolean social = seconds % 10 == 0;
        for (UUID id : List.copyOf(pets.keySet())) {
            Player owner = Bukkit.getPlayer(id);
            List<Fox> owned = owned(id);
            Fox bedLeader = owner != null && owner.isSleeping()
                ? leader(owned.stream().filter(fox -> nearby(owner, fox, DEFENSE_RADIUS)).toList()) : null;
            for (Fox fox : owned) {
                Intent intent = intents.get(fox.getUniqueId());
                if (intent != null && (owner == null || !fox.getWorld().equals(owner.getWorld()) || seconds >= intent.until())) clearIntent(fox);
                if (owner != null && owner.isSleeping() && fox.getWorld().equals(owner.getWorld())) trySleep(owner, fox, fox.equals(bedLeader));
                else if (bedSleep.remove(fox.getUniqueId())) fox.setSleeping(false);
            }
            if (social && owner != null && canUse(owner) && !owner.isSleeping()) socialize(owner, owned);
        }
    }

    private void trySleep(Player owner, Fox fox, boolean onBed) {
        double distance = fox.getLocation().distanceSquared(owner.getLocation());
        if (distance > DEFENSE_RADIUS * DEFENSE_RADIUS) return;
        clearIntent(fox);
        if (distance <= 5) {
            if (bedSleep.add(fox.getUniqueId())) {
                fox.getPathfinder().stopPathfinding();
                fox.setSitting(false);
                if (onBed) placeAtBedFoot(owner, fox);
                fox.setSleeping(true);
            }
        } else {
            wake(fox);
            fox.setSitting(false);
            fox.getPathfinder().moveTo(owner, 1.1);
        }
    }

    private void placeAtBedFoot(Player owner, Fox fox) {
        var bed = owner.getBedLocation().getBlock();
        if (!(bed.getBlockData() instanceof Bed data)) return;
        if (data.getPart() == Bed.Part.HEAD) bed = bed.getRelative(data.getFacing().getOppositeFace());
        var overhead = bed.getRelative(org.bukkit.block.BlockFace.UP);
        if (!(bed.getBlockData() instanceof Bed) || !overhead.isPassable() || overhead.isLiquid()) return;
        fox.teleport(bed.getLocation().add(.5, .5625, .5));
    }

    private boolean nearby(Player owner, Fox fox, int radius) {
        return fox.getWorld().equals(owner.getWorld())
            && fox.getLocation().distanceSquared(owner.getLocation()) <= radius * radius;
    }

    private void wake(Fox fox) {
        if (bedSleep.remove(fox.getUniqueId()) || fox.isSleeping()) fox.setSleeping(false);
    }

    private void socialize(Player owner, List<Fox> owned) {
        List<Fox> nearby = owned.stream().filter(fox -> nearby(owner, fox, SOCIAL_RADIUS)
            && !intents.containsKey(fox.getUniqueId())).toList();
        List<Fox> high = nearby.stream().filter(fox -> affection(fox, owner.getUniqueId()) >= HIGH_AFFECTION).toList();
        if (high.size() > 1 && random.nextDouble() < .019) {
            Fox alpha = leader(high);
            List<Fox> challengers = high.stream().filter(fox -> !fox.equals(alpha)
                && fox.getLocation().distanceSquared(alpha.getLocation()) <= 64).toList();
            if (!challengers.isEmpty()) {
                Fox challenger = challengers.get(random.nextInt(challengers.size()));
                wake(alpha);
                wake(challenger);
                alpha.setSitting(false);
                challenger.setSitting(false);
                engage(alpha, challenger, true);
                engage(challenger, alpha, true);
            }
        }
        for (Fox fox : nearby) {
            double probability = Math.min(.30, affection(fox, owner.getUniqueId()) / 1000.0);
            if (!intents.containsKey(fox.getUniqueId()) && random.nextDouble() < probability) {
                showAffection(owner, fox);
                if (nearby.size() > 1 && nearby(owner, fox, 4)) promote(fox, rank(fox));
            }
        }
    }

    private void showAffection(Player owner, Fox fox) {
        wake(fox);
        fox.setSitting(false);
        switch (random.nextInt(3)) {
            case 0 -> {
                fox.getPathfinder().moveTo(owner, .85);
                if (fox.getLocation().distanceSquared(owner.getLocation()) < 2.25) {
                    fox.setSitting(true);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (fox.isValid() && !bedSleep.contains(fox.getUniqueId())) fox.setSitting(false);
                    }, 40L);
                }
            }
            case 1 -> fox.getPathfinder().moveTo(owner, 1.5);
            default -> {
                fox.getPathfinder().moveTo(owner, 1.1);
                fox.lookAt(owner);
                fox.setInterested(true);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (fox.isValid()) fox.setInterested(false);
                }, 15L);
            }
        }
        heart(fox);
    }

    private Fox leader(List<Fox> foxes) {
        return foxes.stream().max(java.util.Comparator.comparingInt(this::rank).thenComparing(Fox::getUniqueId)).orElse(null);
    }

    private int rank(Fox fox) {
        return fox.getPersistentDataContainer().getOrDefault(rankKey, PersistentDataType.INTEGER, 0);
    }

    private UUID owner(Fox fox) {
        String value = fox.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return value == null ? null : UUID.fromString(value);
    }

    private int affection(Fox fox, UUID person) {
        return fox.getPersistentDataContainer().getOrDefault(affectionKey(person), PersistentDataType.INTEGER, 0);
    }

    private int adjust(Fox fox, UUID person, int amount) {
        int value = Math.clamp(affection(fox, person) + amount, 0, 1000);
        NamespacedKey key = affectionKey(person);
        if (value > 0) fox.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
        else fox.getPersistentDataContainer().remove(key);
        return value;
    }

    private NamespacedKey affectionKey(UUID person) {
        return new NamespacedKey(plugin, "fox_affection_" + person);
    }

    private String displayAffection(int affection) {
        return String.format(Locale.ROOT, "%.1f", affection / 10.0);
    }

    private void track(Fox fox) {
        UUID owner = owner(fox);
        if (owner != null) pets.computeIfAbsent(owner, key -> new HashSet<>()).add(fox.getUniqueId());
    }

    private void untrack(Fox fox) {
        UUID owner = owner(fox);
        if (owner != null) {
            Set<UUID> ids = pets.get(owner);
            if (ids != null) {
                ids.remove(fox.getUniqueId());
                if (ids.isEmpty()) pets.remove(owner);
            }
            intents.remove(fox.getUniqueId());
            sparHits.remove(fox.getUniqueId());
            if (bedSleep.remove(fox.getUniqueId())) fox.setSleeping(false);
        }
    }

    private List<Fox> owned(UUID owner) {
        Set<UUID> ids = pets.get(owner);
        if (ids == null) return List.of();
        return ids.stream().map(Bukkit::getEntity).filter(Fox.class::isInstance).map(Fox.class::cast)
            .filter(fox -> fox.isValid() && owner.equals(owner(fox))).toList();
    }

    private boolean canUse(Player player) {
        return player.isOnline() && player.hasPermission("townyeconomylaw.use") && !government.jailed(player);
    }

    private boolean gloved(Player player) {
        return herbs.kind(player.getInventory().getItemInMainHand()) == GameHerbs.Kind.GLOVES
            || herbs.kind(player.getInventory().getItemInOffHand()) == GameHerbs.Kind.GLOVES;
    }

    private void consume(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else item.setAmount(item.getAmount() - 1);
    }

    private void heart(Fox fox) {
        fox.getWorld().spawnParticle(Particle.HEART, fox.getLocation().add(0, .6, 0), 3, .24, .2, .24, 0);
    }

    private String plain(Component name) {
        return PlainTextComponentSerializer.plainText().serialize(name);
    }
}
