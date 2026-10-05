package net.watones.novagems.fly;

import java.util.Collection;

/**
 * Why a player may fly right now. A temporary grant carries the moment it runs out and whether it
 * came from a rank (temporary parent group) or a directly bought permission.
 */
public record FlyGrant(Kind kind, long expiresAtMillis, boolean fromRank) {
  public enum Kind {
    /** No fly permission at all. */
    NONE,
    /** Permission without an end date (permanent rank, op, wildcard...). */
    PERMANENT,
    /** Permission that ends at {@link #expiresAtMillis()}. */
    TEMPORARY,
    /** Every source was temporary and all of them have already run out. */
    EXPIRED
  }

  public static final FlyGrant NONE = new FlyGrant(Kind.NONE, 0, false);
  public static final FlyGrant PERMANENT = new FlyGrant(Kind.PERMANENT, 0, false);
  public static final FlyGrant EXPIRED = new FlyGrant(Kind.EXPIRED, 0, false);

  public boolean allowsFlight() {
    return kind == Kind.PERMANENT || kind == Kind.TEMPORARY;
  }

  /** One node that grants fly: a permission or a parent group. {@code expiresAtMillis} null = never. */
  public record Source(Long expiresAtMillis, boolean rank) {}

  /**
   * Combines every source the player holds. Callers only ask once the server already said the
   * player has the permission, so finding no source (wildcards, nested groups) means it comes from
   * somewhere without an end date and is treated as permanent rather than taken away.
   */
  public static FlyGrant resolve(Collection<Source> sources, long nowMillis) {
    if (sources.isEmpty()) return PERMANENT;
    long latest = 0;
    boolean latestFromRank = false;
    for (Source source : sources) {
      Long expiresAt = source.expiresAtMillis();
      if (expiresAt == null) return PERMANENT;
      if (expiresAt > nowMillis && expiresAt > latest) {
        latest = expiresAt;
        latestFromRank = source.rank();
      }
    }
    return latest == 0 ? EXPIRED : new FlyGrant(Kind.TEMPORARY, latest, latestFromRank);
  }
}
