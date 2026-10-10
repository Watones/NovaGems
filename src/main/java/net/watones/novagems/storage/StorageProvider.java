package net.watones.novagems.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.watones.novagems.economy.GemTransaction;
import net.watones.novagems.economy.EconomyOperation;
import net.watones.novagems.economy.PlayerAccount;
import net.watones.novagems.economy.TransactionStatus;

public interface StorageProvider extends AutoCloseable {
  void initialize() throws Exception;

  Optional<PlayerAccount> loadAccount(UUID uuid) throws Exception;

  Optional<UUID> findUuidByName(String name) throws Exception;

  /**
   * Every account whose last known name matches, most recently updated first. More than one means
   * the name is ambiguous: a player renamed without rejoining and someone else took the name.
   */
  default List<AccountRef> findAccountsByName(String name) throws Exception {
    return findUuidByName(name).map(uuid -> List.of(new AccountRef(uuid, name))).orElseGet(List::of);
  }

  PlayerAccount loadOrCreate(UUID uuid, String name) throws Exception;

  DurableMutationResult applyOperation(EconomyOperation operation) throws Exception;

  void markDelivery(UUID operationId, TransactionStatus status, String error) throws Exception;

  Optional<GemTransaction> findTransaction(UUID operationId) throws Exception;

  List<LeaderboardEntry> leaderboard(int offset, int limit) throws Exception;

  List<GemTransaction> history(UUID uuid, int offset, int limit) throws Exception;

  List<GemTransaction> deliveryFailures(int offset, int limit) throws Exception;

  /** Purchases waiting for a staff decision, newest first. */
  default List<GemTransaction> manualReviews(int offset, int limit) throws Exception {
    return deliveryFailures(offset, limit).stream()
        .filter(transaction -> transaction.status() == TransactionStatus.MANUAL_REVIEW)
        .toList();
  }

  long countTransactions(TransactionStatus status) throws Exception;

  default long maxAccountSequence(UUID accountId) throws Exception { return 0; }

  /** No-op unless overridden: caps how high a CREDIT can push a balance. Hot-reloadable. */
  default void configureMaxBalance(long maxBalance) {}

  /** Atomically claims and acknowledges all outstanding playtime notices for one account. */
  default Optional<RewardNotification> claimRewardNotification(UUID accountId) throws Exception {
    return Optional.empty();
  }

  default void resolveManualReview(
      UUID operationId, TransactionStatus newStatus, String adminId, String action, String details)
      throws Exception {
    throw new UnsupportedOperationException("Manual review resolution is not supported");
  }

  default DurableMutationResult resolveManualReviewRefund(UUID operationId, String adminId)
      throws Exception {
    throw new UnsupportedOperationException("Manual review refund is not supported");
  }

  /**
   * Victims each killer already earned a kill reward for on {@code day} (ISO local date). Read once
   * at startup so a restart does not hand everyone a fresh daily allowance.
   */
  default Map<UUID, Set<UUID>> loadDailyKills(String day) throws Exception {
    return Map.of();
  }

  /** Idempotent: re-recording the same killer/victim/day is a no-op. */
  default void recordDailyKill(UUID killer, UUID victim, String day, long timestamp)
      throws Exception {}

  /** Undoes {@link #recordDailyKill} for a kill whose reward was never accepted. */
  default void forgetDailyKill(UUID killer, UUID victim, String day) throws Exception {}

  /** Drops rows for days before {@code day}; the tracker only ever reads the current day. */
  default int pruneDailyKillsBefore(String day) throws Exception {
    return 0;
  }

  /**
   * Deletes settled ledger rows created before {@code cutoffMillis}. Rows that still drive a
   * delivery, a manual review or an unsent reward notice are never touched; balances are unaffected.
   * {@code keep} lists operations that must survive regardless: a row is what makes replaying its
   * recovery record a no-op.
   */
  default int pruneTransactionsBefore(long cutoffMillis, Set<UUID> keep) throws Exception {
    return 0;
  }

  /** Whether {@link #snapshotTo} can copy the live database to a standalone file. */
  default boolean supportsSnapshot() {
    return false;
  }

  /**
   * Writes a transactionally consistent, standalone copy of the live database to {@code target},
   * which must not exist yet. Must not block normal reads and writes while it runs.
   */
  default void snapshotTo(java.nio.file.Path target) throws Exception {
    throw new UnsupportedOperationException("Snapshots are not supported by " + description());
  }

  int workerThreads();

  String description();

  @Override
  void close() throws Exception;

  record LeaderboardEntry(String name, long balance) {}

  record AccountRef(UUID uuid, String name) {}

  record RewardNotification(int rewards, long amount, long balance) {}
}
