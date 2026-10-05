package net.watones.novagems.fly;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;

/** Everything read from fly.yml. Lists are stored lower-case so lookups ignore capitalisation. */
public record FlySettings(
    boolean enabled,
    Set<String> permissions,
    boolean enableOnJoin,
    boolean removeWithoutPermission,
    Set<String> blockedWorlds,
    Set<String> blockedRegions,
    boolean combatEnabled,
    int combatTagSeconds,
    boolean slowFallingEnabled,
    int slowFallingMaxSeconds,
    boolean actionbarEnabled,
    String actionbarFormat,
    String labelPermission,
    String labelRank,
    String messageCombat,
    String messageBlockedZone,
    String messageExpired,
    String messageNoPermission,
    String messageRestored) {

  /** Throws on broken YAML or out-of-range values so a bad reload keeps the previous settings. */
  public static FlySettings load(File file) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.load(file);
    Set<String> permissions = lowerCase(yaml.getStringList("permissions"));
    if (permissions.isEmpty()) permissions = Set.of("essentials.fly");
    int combatTag = yaml.getInt("combat.tag-seconds", 15);
    if (combatTag < 1 || combatTag > 600) {
      throw new IllegalArgumentException("fly.yml: combat.tag-seconds debe estar entre 1 y 600");
    }
    int slowFalling = yaml.getInt("slow-falling.max-seconds", 60);
    if (slowFalling < 1 || slowFalling > 600) {
      throw new IllegalArgumentException("fly.yml: slow-falling.max-seconds debe estar entre 1 y 600");
    }
    return new FlySettings(
        yaml.getBoolean("enabled", true),
        permissions,
        yaml.getBoolean("enable-on-join", true),
        yaml.getBoolean("remove-without-permission", true),
        lowerCase(yaml.getStringList("blocked-worlds")),
        lowerCase(yaml.getStringList("blocked-regions")),
        yaml.getBoolean("combat.enabled", true),
        combatTag,
        yaml.getBoolean("slow-falling.enabled", true),
        slowFalling,
        yaml.getBoolean("actionbar.enabled", true),
        yaml.getString("actionbar.format",
            "<aqua>» <bold>¡VUELO ACTIVADO!</bold> <gold>▸ <label>: <yellow><time>"),
        yaml.getString("actionbar.label-permission", "Tiempo Fly"),
        yaml.getString("actionbar.label-rank", "Tiempo Rango"),
        yaml.getString("messages.combat", ""),
        yaml.getString("messages.blocked-zone", ""),
        yaml.getString("messages.expired", ""),
        yaml.getString("messages.no-permission", ""),
        yaml.getString("messages.restored", ""));
  }

  private static Set<String> lowerCase(List<String> values) {
    Set<String> out = new LinkedHashSet<>();
    for (String value : values) {
      if (value != null && !value.isBlank()) out.add(value.trim().toLowerCase(Locale.ROOT));
    }
    return Set.copyOf(out);
  }
}
