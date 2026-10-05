package net.watones.novagems.fly;

/** Formats the remaining fly time for the action bar: {@code 8d 5h 58m 29s}, {@code 4m 03s}. */
public final class FlyTime {
  private FlyTime() {}

  /** Leading empty units are dropped, so the bar never shows {@code 0d 0h 0m 9s}. */
  public static String format(long totalSeconds) {
    long seconds = Math.max(0, totalSeconds);
    long days = seconds / 86_400;
    long hours = seconds % 86_400 / 3_600;
    long minutes = seconds % 3_600 / 60;
    long secs = seconds % 60;
    StringBuilder out = new StringBuilder(16);
    if (days > 0) out.append(days).append("d ");
    if (days > 0 || hours > 0) out.append(hours).append("h ");
    if (days > 0 || hours > 0 || minutes > 0) out.append(minutes).append("m ");
    return out.append(secs).append('s').toString();
  }

  /** Seconds left, rounded up so the bar reads 1s (not 0s) during the final second. */
  public static long secondsLeft(long expiresAtMillis, long nowMillis) {
    long millis = expiresAtMillis - nowMillis;
    return millis <= 0 ? 0 : (millis + 999) / 1000;
  }
}
