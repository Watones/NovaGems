package net.watones.novagems.fly;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps /fly on through teleports, vehicles, deaths and game mode changes; takes it away in combat,
 * blocked worlds/regions and when a temporary grant runs out; shows the remaining time.
 *
 * <p>Everything here runs on the main thread (events and a once-per-second task), so the plain
 * collections need no locking. Per-player entries are dropped on quit.
 */
public final class FlyService {
  static final String BYPASS_PERMISSION = "novagems.fly.bypass";
  /** Some plugins switch flight off a few ticks after a teleport, so restore twice. */
  private static final long[] RESTORE_DELAYS_TICKS = {1L, 5L};

  private enum Block { COMBAT, ZONE }

  private final JavaPlugin plugin;
  private final FlyTimeSource time;
  private final RegionLookup regions;
  private final MiniMessage mini = MiniMessage.miniMessage();
  private volatile FlySettings settings;

  private final Map<UUID, Long> combatUntil = new HashMap<>();
  /** Lost fly to combat or a blocked zone; it comes back when that ends. */
  private final Set<UUID> suspended = new HashSet<>();
  /** Carry our slow falling until they land. */
  private final Set<UUID> softLanding = new HashSet<>();
  /** Rode a vehicle with fly on; value = was flying when they got in. */
  private final Map<UUID, Boolean> inVehicle = new HashMap<>();
  private final Set<UUID> diedWithFly = new HashSet<>();
  /**
   * Seen flying on a temporary grant. If LuckPerms deletes the expired node before we notice, the
   * grant reads NONE instead of EXPIRED; this still lets us treat it as the timer running out.
   */
  private final Set<UUID> timed = new HashSet<>();
  /**
   * Had fly on at the last check. Sign elevators and warp plugins sometimes switch flight off just
   * before teleporting, so by the time the teleport event fires it already reads as off.
   */
  private final Set<UUID> hadFlyLastSecond = new HashSet<>();
  private BukkitTask task;

  public FlyService(JavaPlugin plugin, FlySettings settings, FlyTimeSource time,
      RegionLookup regions) {
    this.plugin = plugin;
    this.settings = settings;
    this.time = time;
    this.regions = regions;
  }

  public void start() {
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
  }

  public void stop() {
    if (task != null) task.cancel();
  }

  public void apply(FlySettings next) {
    settings = next;
  }

  boolean enabled() {
    return settings.enabled();
  }

  // ---------------------------------------------------------------- events

  void onJoin(Player player) {
    FlySettings s = settings;
    if (!s.enabled() || !s.enableOnJoin() || !canFlyHere(player.getGameMode())) return;
    if (!hasAnyPermission(player, s)) return;
    // Joining mid-air (logged out while flying): appear flying instead of dropping.
    scheduleRestore(player, isAirborne(player));
  }

  /** Teleports of any kind: homes, warps, /tp, sign elevators, pearls, portals. */
  void onTeleport(Player player) {
    if (enabled() && hadFly(player)) scheduleRestore(player, player.isFlying());
  }

  void onGameModeChange(Player player, GameMode next) {
    if (!enabled() || !canFlyHere(next)) return;
    // Creative always allows flight, so leaving creative restores it for those with permission.
    if (hadFly(player)) scheduleRestore(player, player.isFlying());
  }

  void onVehicleEnter(Player player) {
    if (enabled() && hadFly(player)) inVehicle.put(player.getUniqueId(), player.isFlying());
  }

  void onVehicleExit(Player player) {
    Boolean wasFlying = inVehicle.remove(player.getUniqueId());
    if (wasFlying != null && enabled()) scheduleRestore(player, wasFlying);
  }

  void onDeath(Player player) {
    UUID id = player.getUniqueId();
    combatUntil.remove(id);
    inVehicle.remove(id);
    if (enabled() && hadFly(player)) diedWithFly.add(id);
  }

  void onRespawn(Player player) {
    if (diedWithFly.remove(player.getUniqueId()) && enabled()) scheduleRestore(player, false);
  }

  /** Double-jump to start flying: refused on the spot in combat or a blocked zone. */
  boolean refuseTakeOff(Player player) {
    if (!enabled() || !canFlyHere(player.getGameMode())) return false;
    Block block = blockReason(player, System.currentTimeMillis());
    if (block == null) return false;
    suspend(player, block);
    return true;
  }

  /** A player hit another player (or got hit): both lose fly for the configured time. */
  void tagCombat(Player player) {
    FlySettings s = settings;
    if (!s.enabled() || !s.combatEnabled() || player.hasPermission(BYPASS_PERMISSION)) return;
    combatUntil.put(player.getUniqueId(),
        System.currentTimeMillis() + s.combatTagSeconds() * 1000L);
    if (player.getAllowFlight() && canFlyHere(player.getGameMode())) {
      suspend(player, Block.COMBAT);
    }
  }

  void forget(UUID id) {
    combatUntil.remove(id);
    suspended.remove(id);
    softLanding.remove(id);
    inVehicle.remove(id);
    diedWithFly.remove(id);
    timed.remove(id);
    hadFlyLastSecond.remove(id);
  }

  // ---------------------------------------------------------------- once per second

  private void tick() {
    FlySettings s = settings;
    long now = System.currentTimeMillis();
    combatUntil.values().removeIf(until -> until <= now);
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID id = player.getUniqueId();
      if (!softLanding.isEmpty() && softLanding.contains(id)) checkLanding(player);
      if (!s.enabled() || !canFlyHere(player.getGameMode())) continue;
      if (player.getAllowFlight()) {
        hadFlyLastSecond.add(id);
        checkFlyer(player, s, now);
      } else {
        hadFlyLastSecond.remove(id);
        if (suspended.contains(id)) tryResume(player, s, now);
      }
    }
  }

  private void checkFlyer(Player player, FlySettings s, long now) {
    if (player.hasPermission(BYPASS_PERMISSION)) return;
    FlyGrant grant = grant(player, s, now);
    UUID id = player.getUniqueId();
    boolean wasTimed = timed.remove(id);
    if (grant.kind() == FlyGrant.Kind.TEMPORARY) timed.add(id);
    if (grant.kind() == FlyGrant.Kind.EXPIRED
        || (grant.kind() == FlyGrant.Kind.NONE && wasTimed)) {
      disable(player);
      send(player, s.messageExpired());
      time.removeExpired(player);
      return;
    }
    if (grant.kind() == FlyGrant.Kind.NONE) {
      if (s.removeWithoutPermission()) {
        disable(player);
        send(player, s.messageNoPermission());
      }
      return;
    }
    Block block = blockReason(player, now);
    if (block != null) {
      suspend(player, block);
      return;
    }
    if (grant.kind() == FlyGrant.Kind.TEMPORARY && s.actionbarEnabled()) {
      String label = grant.fromRank() ? s.labelRank() : s.labelPermission();
      String left = FlyTime.format(FlyTime.secondsLeft(grant.expiresAtMillis(), now));
      player.sendActionBar(mini.deserialize(s.actionbarFormat(),
          Placeholder.parsed("label", label), Placeholder.unparsed("time", left)));
    }
  }

  private void tryResume(Player player, FlySettings s, long now) {
    if (!grant(player, s, now).allowsFlight()) {
      suspended.remove(player.getUniqueId());
      timed.remove(player.getUniqueId());
      return;
    }
    if (blockReason(player, now) != null) return;
    suspended.remove(player.getUniqueId());
    player.setAllowFlight(true);
    send(player, s.messageRestored());
  }

  // ---------------------------------------------------------------- helpers

  private void scheduleRestore(Player player, boolean wasFlying) {
    for (long delay : RESTORE_DELAYS_TICKS) {
      Bukkit.getScheduler().runTaskLater(plugin, () -> restore(player, wasFlying), delay);
    }
  }

  /** Re-checks everything after the move: the permission may depend on the new world. */
  private void restore(Player player, boolean wasFlying) {
    FlySettings s = settings;
    if (!s.enabled() || !player.isOnline() || player.isDead()) return;
    if (!canFlyHere(player.getGameMode())) return;
    long now = System.currentTimeMillis();
    if (!grant(player, s, now).allowsFlight()) return;
    Block block = blockReason(player, now);
    if (block != null) {
      if (player.getAllowFlight()) suspend(player, block);
      else suspended.add(player.getUniqueId());
      return;
    }
    suspended.remove(player.getUniqueId());
    if (!player.getAllowFlight()) player.setAllowFlight(true);
    if (wasFlying && !player.isFlying() && !player.isInsideVehicle()) player.setFlying(true);
  }

  private void suspend(Player player, Block block) {
    FlySettings s = settings;
    disable(player);
    suspended.add(player.getUniqueId());
    send(player, block == Block.COMBAT ? s.messageCombat() : s.messageBlockedZone());
  }

  private void disable(Player player) {
    hadFlyLastSecond.remove(player.getUniqueId());
    boolean airborne = player.isFlying() || isAirborne(player);
    player.setFlying(false);
    player.setAllowFlight(false);
    FlySettings s = settings;
    if (airborne && s.slowFallingEnabled()) {
      // Ambient + no particles marks it as ours, so landing removes it without touching potions.
      player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
          s.slowFallingMaxSeconds() * 20, 0, true, false, true));
      softLanding.add(player.getUniqueId());
    }
  }

  private void checkLanding(Player player) {
    PotionEffect effect = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
    if (effect == null) {
      softLanding.remove(player.getUniqueId());
      return;
    }
    if (isAirborne(player)) return;
    if (effect.isAmbient() && !effect.hasParticles()) {
      player.removePotionEffect(PotionEffectType.SLOW_FALLING);
    }
    softLanding.remove(player.getUniqueId());
  }

  private Block blockReason(Player player, long now) {
    FlySettings s = settings;
    if (player.hasPermission(BYPASS_PERMISSION)) return null;
    if (s.combatEnabled()) {
      Long until = combatUntil.get(player.getUniqueId());
      if (until != null && until > now) return Block.COMBAT;
    }
    Location location = player.getLocation();
    if (!s.blockedWorlds().isEmpty()
        && s.blockedWorlds().contains(location.getWorld().getName().toLowerCase(Locale.ROOT))) {
      return Block.ZONE;
    }
    if (regions.insideAny(location, s.blockedRegions())) return Block.ZONE;
    return null;
  }

  private FlyGrant grant(Player player, FlySettings s, long now) {
    if (!hasAnyPermission(player, s)) return FlyGrant.NONE;
    return time.lookup(player, s.permissions(), now);
  }

  private static boolean hasAnyPermission(Player player, FlySettings s) {
    for (String permission : s.permissions()) {
      if (player.hasPermission(permission)) return true;
    }
    return false;
  }

  /** Fly on now or a second ago, or taken away by us for a reason that may already be over. */
  private boolean hadFly(Player player) {
    UUID id = player.getUniqueId();
    return player.getAllowFlight() || hadFlyLastSecond.contains(id) || suspended.contains(id);
  }

  private static boolean canFlyHere(GameMode mode) {
    return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
  }

  private static boolean isAirborne(Player player) {
    if (player.isInsideVehicle() || player.isInWater()) return false;
    return player.getLocation().subtract(0, 0.1, 0).getBlock().isPassable();
  }

  private void send(Player player, String template) {
    if (template != null && !template.isBlank()) player.sendMessage(mini.deserialize(template));
  }
}
