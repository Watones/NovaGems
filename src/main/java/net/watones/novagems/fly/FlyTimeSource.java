package net.watones.novagems.fly;

import java.util.Set;
import org.bukkit.entity.Player;

/** Where fly permissions come from and when they end. Only LuckPerms knows the end dates. */
public interface FlyTimeSource {
  /** Called only for players that currently pass {@code hasPermission} for one of the nodes. */
  FlyGrant lookup(Player player, Set<String> permissions, long nowMillis);

  /** Drops nodes whose time ran out right away instead of waiting for the permission plugin. */
  void removeExpired(Player player);

  /** Only call when LuckPerms is enabled; the LuckPerms classes load on first use. */
  static FlyTimeSource luckPerms() {
    return new LuckPermsFlyTimeSource();
  }

  /** Without LuckPerms nothing has an end date: no timer, nothing to expire. */
  FlyTimeSource PERMANENT_ONLY = new FlyTimeSource() {
    @Override public FlyGrant lookup(Player player, Set<String> permissions, long nowMillis) {
      return FlyGrant.PERMANENT;
    }

    @Override public void removeExpired(Player player) {}
  };
}
