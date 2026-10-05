package net.watones.novagems.fly;

import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Location;

/** Answers "is this spot inside one of these regions?". */
public interface RegionLookup {
  boolean insideAny(Location location, Set<String> lowerCaseRegionIds);

  RegionLookup NONE = (location, ids) -> false;

  /** Only call when WorldGuard is enabled; falls back to {@link #NONE} if its API cannot be reached. */
  static RegionLookup worldGuard(Logger logger) {
    return WorldGuardRegions.create(logger);
  }
}
