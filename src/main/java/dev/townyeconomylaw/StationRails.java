package dev.townyeconomylaw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.util.Vector;

import static dev.townyeconomylaw.State.require;

final class StationRails implements Listener {
    static final int DEFAULT_SECONDS = 5;
    static final int MAX_SECONDS = 60;

    private record Cart(Minecart entity, double originalSpeed) {}
    private record Stop(Block rail, Minecart lead, List<Cart> carts, Vector launch, long releaseAt) {}

    private final State state;
    private final EnterpriseLots lots;
    private final Enterprises firms;
    private final MinecartCouplers couplers;
    private final Map<UUID, Stop> stops = new HashMap<>();
    private final Set<UUID> held = new HashSet<>();
    private long time;

    StationRails(State state, EnterpriseLots lots, Enterprises firms, MinecartCouplers couplers) {
        this.state = state;
        this.lots = lots;
        this.firms = firms;
        this.couplers = couplers;
    }

    static Block target(Player player) {
        Block lookedAt = player.getTargetBlockExact(5);
        if (lookedAt != null && lookedAt.getType() == Material.RAIL) return lookedAt;
        Block feet = player.getLocation().getBlock();
        if (feet.getType() == Material.RAIL) return feet;
        Block below = feet.getRelative(BlockFace.DOWN);
        return below.getType() == Material.RAIL ? below : lookedAt;
    }

    void configure(Player player, Enterprises.Firm firm, Block rail, int seconds) {
        firms.leader(player, firm);
        require(rail != null, "5ブロック以内のレールを見てください。");
        String entry = path(rail);
        String registeredFirm = state.data().getString(entry + ".firm");
        if (seconds == 0) {
            require(firm.id().equals(registeredFirm), "この組織のステーションレールがありません。");
            remove(List.of(rail));
            player.sendMessage("ステーションレールを解除しました。");
            return;
        }

        require(seconds > 0 && seconds <= MAX_SECONDS, "停車時間は1〜" + MAX_SECONDS + "秒です。");
        require(shape(rail) != null, "平坦で直線の通常レールを見てください。");
        EnterpriseLots.Claim claim = lots.at(rail.getLocation());
        require(claim != null && "station".equals(claim.lot().type()) && lots.accessible(claim, firm),
            "自社または共有された駅ロットのレールを指定してください。");
        require(registeredFirm == null || firm.id().equals(registeredFirm) || !validRegistration(rail, entry),
            "このステーションレールは別の組織が管理しています。");
        state.transaction(undo -> {
            state.data().set(entry + ".firm", firm.id());
            state.data().set(entry + ".plot", claim.key());
            state.data().set(entry + ".plot-owner", claim.firm().id());
            state.data().set(entry + ".seconds", seconds);
        });
        player.sendMessage("ステーションレールを登録しました。停車 " + seconds + " 秒");
    }

    private String path(Block block) {
        return "station-rails." + block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private Rail.Shape shape(Block block) {
        if (block.getType() != Material.RAIL || !(block.getBlockData() instanceof Rail data)) return null;
        return switch (data.getShape()) {
            case NORTH_SOUTH, EAST_WEST -> data.getShape();
            default -> null;
        };
    }

    private boolean validRegistration(Block block, String entry) {
        String id = state.data().getString(entry + ".firm");
        Enterprises.Firm firm = id == null ? null : firms.get(id);
        if (firm == null) return false;
        EnterpriseLots.Claim claim = lots.at(block.getLocation());
        return claim != null && "station".equals(claim.lot().type())
            && claim.key().equals(state.data().getString(entry + ".plot"))
            && claim.firm().id().equals(state.data().getString(entry + ".plot-owner"))
            && lots.accessible(claim, firm);
    }

    boolean held(Minecart cart) {
        return held.contains(cart.getUniqueId());
    }

    @EventHandler
    public void arrive(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart lead) || held(lead) || couplers.hasFront(lead)) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (!to.getWorld().equals(from.getWorld()) || (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ())) return;
        Block rail = to.getBlock();
        String entry = path(rail);
        int seconds = state.data().getInt(entry + ".seconds", 0);
        if (seconds < 1 || !validRegistration(rail, entry)) return;
        Rail.Shape shape = shape(rail);
        if (shape == null) return;

        boolean eastWest = shape == Rail.Shape.EAST_WEST;
        double approach = eastWest ? to.getX() - from.getX() : to.getZ() - from.getZ();
        if (Math.abs(approach) < 0.0001) return;

        List<Minecart> train = couplers.train(lead);
        if (train.stream().anyMatch(this::held)) return;
        Vector velocity = lead.getVelocity();
        double speed = Math.max(0.12, Math.max(Math.abs(approach), Math.abs(eastWest ? velocity.getX() : velocity.getZ())));
        double restart = Math.copySign(Math.min(speed, lead.getMaxSpeed()), approach);
        Vector launch = eastWest ? new Vector(restart, 0, 0) : new Vector(0, 0, restart);
        List<Cart> carts = new ArrayList<>();
        for (Minecart cart : train) {
            carts.add(new Cart(cart, cart.getMaxSpeed()));
            held.add(cart.getUniqueId());
            cart.setMaxSpeed(0);
            cart.setVelocity(new Vector());
            for (var passenger : cart.getPassengers()) {
                if (passenger instanceof Player player) player.sendActionBar(Component.text("停車 " + seconds + " 秒"));
            }
        }
        stops.put(lead.getUniqueId(), new Stop(rail, lead, carts, launch, time + 20L * seconds));
    }

    void tick() {
        time++;
        var active = stops.values().iterator();
        while (active.hasNext()) {
            Stop stop = active.next();
            Minecart lead = stop.lead();
            Block rail = stop.rail();
            boolean atStation = lead.isValid() && lead.getWorld().equals(rail.getWorld())
                && lead.getLocation().distanceSquared(rail.getLocation().clone().add(0.5, 0.5, 0.5)) <= 4.0;
            if (!atStation || time >= stop.releaseAt() || shape(rail) == null || !state.data().contains(path(rail))) {
                release(stop, atStation);
                active.remove();
                continue;
            }

            for (Cart cart : stop.carts()) {
                if (cart.entity().isValid()) cart.entity().setVelocity(new Vector());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void collision(VehicleEntityCollisionEvent event) {
        if (event.getVehicle() instanceof Minecart cart && held(cart)) event.setCancelled(true);
    }

    @EventHandler
    public void unload(EntitiesUnloadEvent event) {
        Set<UUID> unloaded = new HashSet<>();
        for (var entity : event.getEntities()) {
            if (held.contains(entity.getUniqueId())) unloaded.add(entity.getUniqueId());
        }
        if (unloaded.isEmpty()) return;
        var active = stops.values().iterator();
        while (active.hasNext()) {
            Stop stop = active.next();
            if (stop.carts().stream().anyMatch(cart -> unloaded.contains(cart.entity().getUniqueId()))) {
                release(stop, false);
                active.remove();
            }
        }
    }

    void releaseAll() {
        for (Stop stop : stops.values()) release(stop, false);
        stops.clear();
    }

    private void release(Stop stop, boolean depart) {
        for (Cart cart : stop.carts()) {
            held.remove(cart.entity().getUniqueId());
            if (cart.entity().isValid()) cart.entity().setMaxSpeed(cart.originalSpeed());
        }
        if (depart) stop.lead().setVelocity(stop.launch());
    }

    private void remove(List<Block> blocks) {
        List<String> entries = blocks.stream().map(this::path).filter(state.data()::contains).toList();
        if (!entries.isEmpty()) state.transaction(undo -> entries.forEach(entry -> state.data().set(entry, null)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakRail(BlockBreakEvent event) {
        remove(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void replaceRail(BlockPlaceEvent event) {
        remove(List.of(event.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) {
        remove(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) {
        remove(event.blockList());
    }
}
