package dev.townyeconomylaw;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

final class JailGuard implements Listener {
    private final Government government;
    private final Set<UUID> movingToJail = new HashSet<>();

    JailGuard(Government government) {
        this.government = government;
    }

    void sendToJail(Player player) {
        Location anchor = government.anchor(player);
        if (anchor == null) return;
        player.leaveVehicle();
        player.eject();
        movingToJail.add(player.getUniqueId());
        try {
            player.teleport(anchor, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            movingToJail.remove(player.getUniqueId());
        }
    }

    void enforce() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (government.jailed(player) && !government.inside(player, player.getLocation())) sendToJail(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void command(PlayerCommandPreprocessEvent event) {
        if (!government.jailed(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("拘禁中はコマンドを使用できません。");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (government.jailed(player) && (!movingToJail.contains(player.getUniqueId())
                || !government.anchor(player).equals(event.getTo()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void portal(PlayerPortalEvent event) {
        if (government.jailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void move(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (government.jailed(player) && event.getTo() != null && !government.inside(player, event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent event) {
        if (!government.jailed(event.getPlayer())) return;
        Location anchor = government.anchor(event.getPlayer());
        if (anchor != null) event.setRespawnLocation(anchor);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void breakBlock(BlockBreakEvent event) {
        if (government.jailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void place(BlockPlaceEvent event) {
        if (government.jailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (government.jailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interactEntity(PlayerInteractEntityEvent event) {
        if (government.jailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void enterVehicle(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && government.jailed(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void projectile(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && government.jailed(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void attack(EntityDamageByEntityEvent event) {
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
        if (source instanceof TNTPrimed tnt && tnt.getSource() != null) source = tnt.getSource();
        if (source instanceof Player player && government.jailed(player)) event.setCancelled(true);
    }
}
