package net.watones.novagems.fly;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;

/**
 * WorldGuard region check through its public API, called reflectively so NovaGems builds and runs
 * without WorldGuard installed. Equivalent to:
 * {@code WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
 * .getApplicableRegions(BukkitAdapter.adapt(location))}.
 */
final class WorldGuardRegions implements RegionLookup {
  private final Logger logger;
  private final Object container;
  private final Method createQuery;
  private final Method adapt;
  private final Method applicableRegions;
  private final Method regionId;
  private volatile boolean failed;

  private WorldGuardRegions(Logger logger, Object container, Method createQuery, Method adapt,
      Method applicableRegions, Method regionId) {
    this.logger = logger;
    this.container = container;
    this.createQuery = createQuery;
    this.adapt = adapt;
    this.applicableRegions = applicableRegions;
    this.regionId = regionId;
  }

  static RegionLookup create(Logger logger) {
    try {
      ClassLoader loader = WorldGuardRegions.class.getClassLoader();
      Class<?> worldGuard = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
      Method getPlatform = worldGuard.getMethod("getPlatform");
      Method getContainer = getPlatform.getReturnType().getMethod("getRegionContainer");
      Object platform = getPlatform.invoke(worldGuard.getMethod("getInstance").invoke(null));
      Object container = getContainer.invoke(platform);
      Method createQuery = getContainer.getReturnType().getMethod("createQuery");
      Class<?> weLocation = Class.forName("com.sk89q.worldedit.util.Location", true, loader);
      Method adapt = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader)
          .getMethod("adapt", Location.class);
      Method applicable = createQuery.getReturnType().getMethod("getApplicableRegions", weLocation);
      Method regionId = Class.forName(
          "com.sk89q.worldguard.protection.regions.ProtectedRegion", true, loader).getMethod("getId");
      return new WorldGuardRegions(logger, container, createQuery, adapt, applicable, regionId);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
      logger.log(Level.WARNING, "No se pudo conectar con WorldGuard; blocked-regions de fly.yml"
          + " no tendrá efecto", failure);
      return NONE;
    }
  }

  @Override
  public boolean insideAny(Location location, Set<String> lowerCaseRegionIds) {
    if (lowerCaseRegionIds.isEmpty() || failed) return false;
    try {
      Object query = createQuery.invoke(container);
      Object regions = applicableRegions.invoke(query, adapt.invoke(null, location));
      for (Object region : (Iterable<?>) regions) {
        String id = (String) regionId.invoke(region);
        if (lowerCaseRegionIds.contains(id.toLowerCase(Locale.ROOT))) return true;
      }
      return false;
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
      // Logged once; afterwards regions are skipped instead of spamming every second.
      failed = true;
      logger.log(Level.WARNING, "Fallo consultando regiones de WorldGuard; blocked-regions"
          + " desactivado hasta reiniciar", failure);
      return false;
    }
  }
}
