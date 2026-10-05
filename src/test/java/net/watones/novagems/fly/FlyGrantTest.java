package net.watones.novagems.fly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlyGrantTest {
  private static final long NOW = 1_000_000;

  @Test
  void aPermanentSourceWinsOverAnyTemporaryOne() {
    FlyGrant grant = FlyGrant.resolve(
        List.of(new FlyGrant.Source(NOW + 5_000, false), new FlyGrant.Source(null, true)), NOW);
    assertThat(grant).isEqualTo(FlyGrant.PERMANENT);
  }

  @Test
  void theLatestActiveSourceDecidesTheTimerAndItsLabel() {
    FlyGrant grant = FlyGrant.resolve(
        List.of(
            new FlyGrant.Source(NOW + 5_000, false),
            new FlyGrant.Source(NOW + 90_000, true),
            new FlyGrant.Source(NOW - 1, false)),
        NOW);
    assertThat(grant.kind()).isEqualTo(FlyGrant.Kind.TEMPORARY);
    assertThat(grant.expiresAtMillis()).isEqualTo(NOW + 90_000);
    assertThat(grant.fromRank()).isTrue();
    assertThat(grant.allowsFlight()).isTrue();
  }

  @Test
  void onlyRunOutSourcesMeansExpired() {
    FlyGrant grant = FlyGrant.resolve(
        List.of(new FlyGrant.Source(NOW, false), new FlyGrant.Source(NOW - 60_000, true)), NOW);
    assertThat(grant).isEqualTo(FlyGrant.EXPIRED);
    assertThat(grant.allowsFlight()).isFalse();
  }

  @Test
  void noTraceableSourceIsTreatedAsPermanentInsteadOfTakingFlightAway() {
    assertThat(FlyGrant.resolve(List.of(), NOW)).isEqualTo(FlyGrant.PERMANENT);
  }
}
