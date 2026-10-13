package net.watones.novagems.purchaselog;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One line of the purchase log: what a player bought and how it ended. */
public record PurchaseEvent(
    Kind kind,
    UUID playerId,
    String playerName,
    String rewardId,
    String rewardName,
    int quantity,
    long price,
    long balance,
    UUID operationId,
    Instant at,
    String detail) {
  /** {@link #balance} when the account was not loaded at the time. */
  public static final long UNKNOWN_BALANCE = -1;

  public PurchaseEvent {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(playerName);
    Objects.requireNonNull(rewardId);
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(at);
    rewardName = rewardName == null || rewardName.isBlank() ? rewardId : rewardName;
  }

  public enum Kind {
    /** The reward reached the player. */
    DELIVERED,
    /** The delivery could not happen and the gems went back. */
    REFUNDED,
    /** The outcome is uncertain and staff has to decide. */
    MANUAL_REVIEW
  }
}
