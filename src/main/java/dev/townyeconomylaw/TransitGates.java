package dev.townyeconomylaw;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;

import static dev.townyeconomylaw.State.require;

final class TransitGates implements Listener {
    private record Gate(Enterprises.Firm firm, String station, BlockFace approach, boolean entry, boolean both) {}

    private final TownyEconomyLaw plugin;
    private final State state;
    private final Government government;
    private final Enterprises firms;
    private final EnterpriseLots lots;
    private final Transit transit;
    private final Set<UUID> pending = new HashSet<>();

    TransitGates(TownyEconomyLaw plugin, State state, Government government, Enterprises firms, EnterpriseLots lots, Transit transit) {
        this.plugin = plugin;
        this.state = state;
        this.government = government;
        this.firms = firms;
        this.lots = lots;
        this.transit = transit;
    }

    void configure(Player player, Enterprises.Firm firm, String action) {
        firms.leader(player, firm);
        Block bottom = bottom(player.getTargetBlockExact(5));
        require(bottom != null, "鉄のドアを見ながら実行してください。");
        String path = path(bottom);
        String owner = state.data().getString(path + ".firm");
        if ("remove".equals(action)) {
            require(firm.id().equals(owner), "自社の改札が見つかりません。");
            state.transaction(undo -> state.data().set(path, null));
            player.sendMessage("改札を解除しました。");
            return;
        }

        boolean both = "both".equals(action);
        require(both || "entry".equals(action) || "exit".equals(action), "改札の種類に both、entry、exit のいずれかを指定してください。");
        require(owner == null || firm.id().equals(owner) || registered(bottom) == null,
            "別の企業の改札が登録されています。");
        Door door = closedDoor(bottom);
        require(door != null, "上下が揃った、閉じた鉄のドアを設置してください。");
        BlockFace face = door.getFacing();
        BlockFace approach = sameBlock(player.getLocation(), bottom.getRelative(face)) ? face : face.getOppositeFace();
        require(sameBlock(player.getLocation(), bottom.getRelative(approach)), "通過前に立つ側で、ドアのすぐ隣に立ってください。");

        EnterpriseLots.Claim claim = lots.at(bottom.getLocation());
        require(claim != null && lots.accessible(claim, firm) && "station".equals(claim.lot().type())
            && !claim.lot().station().isBlank(), "駅名を設定した自社または共有ロットに設置してください。");
        require(clearLane(bottom, claim, approach), "改札の両側に、同じ駅ロット内で床と高さ2ブロックの通路を確保してください。");

        state.transaction(undo -> {
            state.data().set(path + ".firm", firm.id());
            state.data().set(path + ".plot", claim.key());
            state.data().set(path + ".station", claim.lot().station());
            state.data().set(path + ".approach", approach.name());
            state.data().set(path + ".entry", !"exit".equals(action));
            state.data().set(path + ".both", both);
        });
        player.sendMessage(switch (action) {
            case "both" -> "両方向の改札を登録しました。現在の側が入場、反対側が出場です。";
            case "entry" -> "入場改札を登録しました。";
            default -> "出場改札を登録しました。";
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block bottom = bottom(event.getClickedBlock());
        if (bottom == null || !state.data().contains(path(bottom))) return;
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || !canUse(player) || !pending.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (canUse(player)) plugin.run(player, () -> pass(player, bottom));
            } finally {
                pending.remove(player.getUniqueId());
            }
        });
    }

    private boolean canUse(Player player) {
        return player.isOnline() && !player.isDead() && player.hasPermission("townyeconomylaw.use")
            && !government.jailed(player);
    }

    private void pass(Player player, Block bottom) {
        Gate gate = registered(bottom);
        Door door = closedDoor(bottom);
        require(gate != null && door != null
            && (gate.approach() == door.getFacing() || gate.approach() == door.getFacing().getOppositeFace()),
            "この改札は使用できません。");
        Location before = player.getLocation();
        BlockFace reverse = gate.approach().getOppositeFace();
        boolean registeredSide = sameBlock(before, bottom.getRelative(gate.approach()));
        require(!player.isInsideVehicle() && (registeredSide || (gate.both() && sameBlock(before, bottom.getRelative(reverse)))),
            "改札を利用できる側で、ドアのすぐ隣に立ってください。");
        EnterpriseLots.Claim claim = lots.at(bottom.getLocation());
        require(claim != null && clearLane(bottom, claim, gate.approach()), "改札の通路が塞がれています。");
        BlockFace destinationSide = registeredSide ? reverse : gate.approach();
        Location destination = bottom.getRelative(destinationSide).getLocation().clone().add(.5, 0, .5);
        destination.setYaw(before.getYaw());
        destination.setPitch(before.getPitch());

        boolean entry = registeredSide ? gate.entry() : !gate.entry();
        transit.gate(player, gate.firm(), gate.station(), entry, undo -> {
            require(player.teleport(destination, TeleportCause.PLUGIN), "改札を通過できませんでした。");
            undo.push(() -> {
                if (!player.teleport(before, TeleportCause.PLUGIN)) {
                    throw new IllegalStateException("Failed to return player after gate rollback");
                }
            });
            Location arrived = player.getLocation();
            require(arrived.getWorld().equals(destination.getWorld()) && arrived.distanceSquared(destination) < .01,
                "改札の移動先が変更されたため、通過を取り消しました。");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void redstone(BlockRedstoneEvent event) {
        Block bottom = bottom(event.getBlock());
        if (bottom != null && registered(bottom) != null) event.setNewCurrent(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        destroy(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) {
        destroy(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) {
        destroy(event.blockList());
    }

    private void destroy(List<Block> blocks) {
        Set<String> paths = new HashSet<>();
        for (Block block : blocks) {
            Block bottom = bottom(block);
            if (bottom != null && state.data().contains(path(bottom))) paths.add(path(bottom));
            Block supported = bottom(block.getRelative(BlockFace.UP));
            if (supported != null && state.data().contains(path(supported))) paths.add(path(supported));
        }
        if (!paths.isEmpty()) state.transaction(undo -> paths.forEach(path -> state.data().set(path, null)));
    }

    private Gate registered(Block bottom) {
        String path = path(bottom);
        String owner = state.data().getString(path + ".firm");
        if (owner == null) return null;
        Enterprises.Firm firm = firms.get(owner);
        EnterpriseLots.Claim claim = firm == null ? null : lots.at(bottom.getLocation());
        String station = state.data().getString(path + ".station");
        if (claim == null || station == null || !claim.key().equals(state.data().getString(path + ".plot"))
            || !lots.accessible(claim, firm) || !"station".equals(claim.lot().type())
            || !station.equalsIgnoreCase(claim.lot().station())) return null;
        BlockFace approach = BlockFace.valueOf(state.data().getString(path + ".approach"));
        return new Gate(firm, station, approach, state.data().getBoolean(path + ".entry"), state.data().getBoolean(path + ".both"));
    }

    private boolean clearLane(Block bottom, EnterpriseLots.Claim claim, BlockFace approach) {
        return clear(bottom.getRelative(approach), claim) && clear(bottom.getRelative(approach.getOppositeFace()), claim);
    }

    private boolean clear(Block block, EnterpriseLots.Claim claim) {
        if (!block.isEmpty() || !block.getRelative(BlockFace.UP).isEmpty()
            || !block.getRelative(BlockFace.DOWN).getType().isSolid()) return false;
        EnterpriseLots.Claim area = lots.at(block.getLocation());
        return area != null && area.firm().id().equals(claim.firm().id()) && area.lot().id().equals(claim.lot().id());
    }

    private static Block bottom(Block block) {
        if (block == null || block.getType() != Material.IRON_DOOR || !(block.getBlockData() instanceof Door data)) return null;
        return data.getHalf() == Bisected.Half.TOP ? block.getRelative(BlockFace.DOWN) : block;
    }

    private static Door closedDoor(Block bottom) {
        Block upper = bottom.getRelative(BlockFace.UP);
        if (bottom.getType() != Material.IRON_DOOR || upper.getType() != Material.IRON_DOOR
            || !(bottom.getBlockData() instanceof Door lower) || !(upper.getBlockData() instanceof Door top)
            || lower.isOpen() || top.isOpen() || lower.isPowered() || top.isPowered()
            || lower.getHalf() != Bisected.Half.BOTTOM || top.getHalf() != Bisected.Half.TOP
            || lower.getFacing() != top.getFacing()) return null;
        return lower;
    }

    private static boolean sameBlock(Location location, Block block) {
        return block.getWorld().equals(location.getWorld()) && location.getBlockX() == block.getX()
            && location.getBlockY() == block.getY() && location.getBlockZ() == block.getZ();
    }

    private static String path(Block bottom) {
        return "transit-gates." + bottom.getWorld().getUID() + ":" + bottom.getX() + ":" + bottom.getY() + ":" + bottom.getZ();
    }
}
