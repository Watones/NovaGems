package net.watones.novagems.fly;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/** Feeds Bukkit events to {@link FlyService}. MONITOR: we react to what really happened. */
public final class FlyListener implements Listener {
  private final FlyService fly;

  public FlyListener(FlyService fly) {
    this.fly = fly;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    fly.onJoin(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onTeleport(PlayerTeleportEvent event) {
    fly.onTeleport(event.getPlayer());
  }

  // Portals have their own event that PlayerTeleportEvent listeners never receive.
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPortal(PlayerPortalEvent event) {
    fly.onTeleport(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGameMode(PlayerGameModeChangeEvent event) {
    fly.onGameModeChange(event.getPlayer(), event.getNewGameMode());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onVehicleEnter(VehicleEnterEvent event) {
    if (event.getEntered() instanceof Player player) fly.onVehicleEnter(player);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onVehicleExit(VehicleExitEvent event) {
    if (event.getExited() instanceof Player player) fly.onVehicleExit(player);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    fly.onDeath(event.getEntity());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onRespawn(PlayerRespawnEvent event) {
    fly.onRespawn(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onToggleFlight(PlayerToggleFlightEvent event) {
    if (event.isFlying() && fly.refuseTakeOff(event.getPlayer())) event.setCancelled(true);
  }

  /** Only real PvP hits that went through (spawn protection cancels them first). */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPvp(EntityDamageByEntityEvent event) {
    if (!fly.enabled()) return;
    if (!(event.getEntity() instanceof Player victim) || isNpc(victim)) return;
    Player attacker = attacker(event.getDamager());
    if (attacker == null || attacker.equals(victim) || isNpc(attacker)) return;
    fly.tagCombat(victim);
    fly.tagCombat(attacker);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    fly.forget(event.getPlayer().getUniqueId());
  }

  private static Player attacker(Entity damager) {
    if (damager instanceof Player player) return player;
    if (damager instanceof Projectile projectile
        && projectile.getShooter() instanceof Player shooter) {
      return shooter;
    }
    return null;
  }

  /** Citizens and similar plugins mark their fake players with this metadata. */
  private static boolean isNpc(Player player) {
    return player.hasMetadata("NPC");
  }
}
