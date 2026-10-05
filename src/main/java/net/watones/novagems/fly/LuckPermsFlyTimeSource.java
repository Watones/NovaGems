package net.watones.novagems.fly;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

/**
 * Reads the player's own nodes: a bought {@code essentials.fly} with an end date, or a temporary
 * rank whose group grants fly. Only loaded when LuckPerms is installed.
 */
final class LuckPermsFlyTimeSource implements FlyTimeSource {
  private final LuckPerms luckPerms = LuckPermsProvider.get();

  @Override
  public FlyGrant lookup(Player player, Set<String> permissions, long nowMillis) {
    User user = luckPerms.getUserManager().getUser(player.getUniqueId());
    if (user == null) return FlyGrant.PERMANENT;
    List<FlyGrant.Source> sources = new ArrayList<>(2);
    for (PermissionNode node : user.getNodes(NodeType.PERMISSION)) {
      if (node.getValue()
          && permissions.contains(node.getPermission().toLowerCase(Locale.ROOT))) {
        sources.add(source(node, false));
      }
    }
    for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
      if (!node.getValue()) continue;
      Group group = luckPerms.getGroupManager().getGroup(node.getGroupName());
      if (group != null && grantsFlight(group, permissions)) sources.add(source(node, true));
    }
    return FlyGrant.resolve(sources, nowMillis);
  }

  @Override
  public void removeExpired(Player player) {
    User user = luckPerms.getUserManager().getUser(player.getUniqueId());
    if (user == null) return;
    user.auditTemporaryNodes();
    luckPerms.getUserManager().saveUser(user);
  }

  private static boolean grantsFlight(Group group, Set<String> permissions) {
    // Non-contextual: a rank that grants fly only in some worlds still counts as the timer's source.
    var data = group.getCachedData().getPermissionData(QueryOptions.nonContextual());
    for (String permission : permissions) {
      if (data.checkPermission(permission).asBoolean()) return true;
    }
    return false;
  }

  private static FlyGrant.Source source(Node node, boolean rank) {
    Instant expiry = node.getExpiry();
    return new FlyGrant.Source(expiry == null ? null : expiry.toEpochMilli(), rank);
  }
}
