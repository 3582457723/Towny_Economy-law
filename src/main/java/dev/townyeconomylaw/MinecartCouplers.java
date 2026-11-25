package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleUpdateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import static dev.townyeconomylaw.State.require;

final class MinecartCouplers implements Listener {
    private static final int TRAIN_LIMIT = 16;
    private static final int SEARCH_LIMIT = 12;
    private static final double JOIN_DISTANCE = 4.0;
    private static final double SPACING = 1.35;
    private static final long SELECTION_NANOS = 30_000_000_000L;

    private record Selection(UUID cart, long time) {}
    private record Exit(int x, int z, int rise) {
        boolean faces(Exit other) {
            return x == -other.x && z == -other.z;
        }
    }
    private record Track(int x, int y, int z, Exit first, Exit second) {
        Vector boundary(Exit exit) {
            return new Vector(x + .5 + exit.x() * .5, 0, z + .5 + exit.z() * .5);
        }
    }
    private record Path(Vector direction, Vector arrival, double distance) {}

    private final TownyEconomyLaw plugin;
    private final Government government;
    private final Integrations api;
    private final NamespacedKey toolKey;
    private final NamespacedKey frontKey;
    private final NamespacedKey backKey;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private Predicate<Minecart> held = cart -> false;

    MinecartCouplers(TownyEconomyLaw plugin, Government government, Integrations api) {
        this.plugin = plugin;
        this.government = government;
        this.api = api;
        toolKey = new NamespacedKey(plugin, "minecart_coupler");
        frontKey = new NamespacedKey(plugin, "coupled_front");
        backKey = new NamespacedKey(plugin, "coupled_back");
        ownerKey = new NamespacedKey(plugin, "coupler_owner");
    }

    void setHeldCheck(Predicate<Minecart> held) {
        this.held = held;
    }

    ItemStack item() {
        ItemStack tool = new ItemStack(Material.IRON_CHAIN);
        var meta = tool.getItemMeta();
        meta.displayName(Component.text("トロッコ連結器"));
        meta.lore(List.of(Component.text("先頭側、後続側の順にトロッコを右クリック"),
            Component.text("スニークしながら右クリックで解除")));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        tool.setItemMeta(meta);
        return tool;
    }

    void issue(Player player) {
        require(player.getInventory().addItem(item()).isEmpty(), "インベントリに空きを作ってください。");
        player.sendMessage("トロッコ連結器を受け取りました。");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Minecart cart) || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!hand.hasItemMeta() || !hand.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (government.jailed(player) || !player.hasPermission("townyeconomylaw.use")) return;
        plugin.run(player, () -> use(player, cart));
    }

    private void use(Player player, Minecart cart) {
        checkControl(player, cart);
        UUID user = player.getUniqueId();
        if (player.isSneaking()) {
            selections.remove(user);
            require(id(cart, frontKey) != null || id(cart, backKey) != null, "このトロッコは連結されていません。");
            disconnect(cart, frontKey, backKey);
            disconnect(cart, backKey, frontKey);
            player.sendMessage("このトロッコの連結を解除しました。");
            return;
        }

        Selection selection = selections.remove(user);
        if (selection == null || System.nanoTime() - selection.time() > SELECTION_NANOS) {
            require(id(cart, backKey) == null, "先頭側には編成の最後尾を選んでください。");
            require(track(cart.getLocation()) != null, "線路上のトロッコを選んでください。");
            selections.put(user, new Selection(cart.getUniqueId(), System.nanoTime()));
            player.sendMessage("先頭側を選択しました。後続側のトロッコを右クリックしてください。");
            return;
        }
        Minecart front = loaded(selection.cart());
        require(front != null && !front.equals(cart), "別の読み込み済みトロッコを選んでください。");
        checkControl(player, front);
        require(id(front, backKey) == null && id(cart, frontKey) == null, "連結には最後尾と先頭を選んでください。");
        Location from = cart.getLocation();
        Location to = front.getLocation();
        require(from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) <= JOIN_DISTANCE * JOIN_DISTANCE,
            "同じワールドの4ブロック以内にトロッコを並べてください。");
        Path track = path(from, to);
        require(track != null && track.distance() >= .55 && track.distance() <= JOIN_DISTANCE,
            "同じ線路上で少し間隔をあけてください。");
        Set<UUID> train = new HashSet<>();
        collect(front, frontKey, backKey, train);
        collect(cart, backKey, frontKey, train);

        save(front, backKey, cart.getUniqueId());
        save(cart, frontKey, front.getUniqueId());
        save(front, ownerKey, user);
        save(cart, ownerKey, user);
        player.sendMessage("トロッコを連結しました。");
    }

    private void checkControl(Player player, Minecart cart) {
        UUID actor = player.getUniqueId();
        UUID owner = id(cart, ownerKey);
        require(owner == null || actor.equals(owner), "連結したプレイヤーのみ操作できます。");
        Integrations.PlotAccess plot = api.plotAccess(cart.getLocation());
        require(plot == null || actor.equals(plot.controller()), "Towny プロットでは区画の管理者のみ連結を操作できます。");
        require(cart.getPassengers().stream().noneMatch(passenger -> passenger instanceof Player && !actor.equals(passenger.getUniqueId())),
            "ほかのプレイヤーが乗車しているトロッコは操作できません。");
    }

    private void collect(Minecart start, NamespacedKey direction, NamespacedKey opposite, Set<UUID> train) {
        Minecart cart = start;
        while (true) {
            require(train.add(cart.getUniqueId()), "同じ編成同士は連結できません。");
            require(train.size() <= TRAIN_LIMIT, "1編成は最大 " + TRAIN_LIMIT + " 両です。");
            UUID next = id(cart, direction);
            if (next == null) return;
            cart = linked(cart, direction, opposite);
            require(cart != null, "編成全体を読み込み、連結状態を確認してください。");
        }
    }

    boolean hasFront(Minecart cart) {
        return id(cart, frontKey) != null;
    }

    List<Minecart> train(Minecart lead) {
        List<Minecart> carts = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Minecart cart = lead; cart != null && carts.size() < TRAIN_LIMIT && seen.add(cart.getUniqueId());
                cart = linked(cart, backKey, frontKey)) {
            carts.add(cart);
        }
        return carts;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void destroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            disconnect(cart, frontKey, backKey);
            disconnect(cart, backKey, frontKey);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void collision(VehicleEntityCollisionEvent event) {
        if (event.getVehicle() instanceof Minecart cart && event.getEntity() instanceof Minecart other
                && (other.equals(linked(cart, frontKey, backKey)) || other.equals(linked(cart, backKey, frontKey)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void update(VehicleUpdateEvent event) {
        if (!(event.getVehicle() instanceof Minecart follower) || !hasFront(follower) || held.test(follower)) return;
        Minecart front = linked(follower, frontKey, backKey);
        if (front == null || held.test(front)) return;
        Location from = follower.getLocation();
        Location to = front.getLocation();
        if (!from.getWorld().equals(to.getWorld()) || from.distanceSquared(to) > SEARCH_LIMIT * SEARCH_LIMIT) {
            disconnect(follower, frontKey, backKey);
            return;
        }

        Path path = path(from, to);
        if (path == null) return;
        double leadingSpeed = front.getVelocity().dot(path.arrival());
        double speed = leadingSpeed + (path.distance() - SPACING) * .30;
        double limit = Math.min(follower.getMaxSpeed(), .55);
        double horizontal = Math.clamp(speed, -limit, limit);
        if (Math.abs(horizontal) < .012) horizontal = 0;
        follower.setVelocity(path.direction().clone().multiply(horizontal).setY(follower.getVelocity().getY()));
    }

    private void disconnect(Minecart cart, NamespacedKey side, NamespacedKey other) {
        Minecart peer = linked(cart, side, other);
        clearSide(cart, side);
        if (peer != null) clearSide(peer, other);
    }

    private void clearSide(Minecart cart, NamespacedKey key) {
        cart.getPersistentDataContainer().remove(key);
        if (id(cart, frontKey) == null && id(cart, backKey) == null) cart.getPersistentDataContainer().remove(ownerKey);
    }

    private Minecart linked(Minecart cart, NamespacedKey side, NamespacedKey opposite) {
        Minecart other = loaded(id(cart, side));
        return other != null && cart.getUniqueId().equals(id(other, opposite)) ? other : null;
    }

    private Minecart loaded(UUID id) {
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        return entity instanceof Minecart cart && cart.isValid() ? cart : null;
    }

    private UUID id(Minecart cart, NamespacedKey key) {
        String value = cart.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return value == null ? null : UUID.fromString(value);
    }

    private void save(Minecart cart, NamespacedKey key, UUID id) {
        cart.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
    }

    private Path path(Location from, Location to) {
        Track first = track(from);
        Track last = track(to);
        if (first == null || last == null) return null;
        Vector start = from.toVector().setY(0);
        Vector end = to.toVector().setY(0);
        if (first.equals(last)) {
            double distance = start.distance(end);
            Vector direction = distance < .05
                ? first.boundary(first.first()).subtract(first.boundary(first.second())).normalize()
                : end.clone().subtract(start).normalize();
            return new Path(direction, direction, distance);
        }
        Path left = follow(from.getWorld(), first, last, first.first(), start, end);
        Path right = follow(from.getWorld(), first, last, first.second(), start, end);
        return left == null || (right != null && right.distance() < left.distance()) ? right : left;
    }

    private Path follow(World world, Track first, Track last, Exit direction, Vector from, Vector to) {
        Track current = first;
        Exit leaving = direction;
        Vector boundary = current.boundary(leaving);
        Vector departure = guide(boundary.clone().subtract(from), leaving);
        double distance = from.distance(boundary);
        for (int step = 0; step < SEARCH_LIMIT; step++) {
            Track next = adjacent(world, current, leaving);
            if (next == null || next.equals(first)) return null;
            Exit entry = next.first().faces(leaving) ? next.first() : next.second();
            Vector point = next.boundary(entry);
            if (next.equals(last)) {
                return new Path(departure, guide(to.clone().subtract(point), leaving), distance + point.distance(to));
            }
            current = next;
            leaving = entry.equals(next.first()) ? next.second() : next.first();
            distance += point.distance(current.boundary(leaving));
        }
        return null;
    }

    private Vector guide(Vector direction, Exit fallback) {
        return direction.lengthSquared() < .0025 ? new Vector(fallback.x(), 0, fallback.z()) : direction.normalize();
    }

    private Track adjacent(World world, Track current, Exit exit) {
        int height = current.y() + exit.rise();
        for (int offset = 0; offset <= 1; offset++) {
            Track next = track(world, current.x() + exit.x(), height - offset, current.z() + exit.z());
            if (next != null && (next.first().faces(exit) && next.first().rise() == offset
                    || next.second().faces(exit) && next.second().rise() == offset)) return next;
        }
        return null;
    }

    private Track track(Location location) {
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        Track current = track(world, x, y, z);
        return current == null ? track(world, x, y - 1, z) : current;
    }

    private Track track(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)
                || !(world.getBlockAt(x, y, z).getBlockData() instanceof Rail rail)) return null;
        Exit north = new Exit(0, -1, 0);
        Exit south = new Exit(0, 1, 0);
        Exit west = new Exit(-1, 0, 0);
        Exit east = new Exit(1, 0, 0);
        return switch (rail.getShape()) {
            case NORTH_SOUTH -> new Track(x, y, z, north, south);
            case EAST_WEST -> new Track(x, y, z, west, east);
            case ASCENDING_NORTH -> new Track(x, y, z, new Exit(0, -1, 1), south);
            case ASCENDING_SOUTH -> new Track(x, y, z, north, new Exit(0, 1, 1));
            case ASCENDING_WEST -> new Track(x, y, z, new Exit(-1, 0, 1), east);
            case ASCENDING_EAST -> new Track(x, y, z, west, new Exit(1, 0, 1));
            case NORTH_EAST -> new Track(x, y, z, north, east);
            case NORTH_WEST -> new Track(x, y, z, north, west);
            case SOUTH_EAST -> new Track(x, y, z, south, east);
            case SOUTH_WEST -> new Track(x, y, z, south, west);
        };
    }
}
