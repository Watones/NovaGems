package net.watones.novagems.fly;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FlyTimeTest {
  @Test
  void dropsOnlyTheLeadingEmptyUnits() {
    assertThat(FlyTime.format(8 * 86_400 + 5 * 3_600 + 58 * 60 + 29)).isEqualTo("8d 5h 58m 29s");
    assertThat(FlyTime.format(5 * 3_600 + 58 * 60 + 29)).isEqualTo("5h 58m 29s");
    assertThat(FlyTime.format(86_400 + 7)).isEqualTo("1d 0h 0m 7s");
    assertThat(FlyTime.format(3_600)).isEqualTo("1h 0m 0s");
    assertThat(FlyTime.format(61)).isEqualTo("1m 1s");
    assertThat(FlyTime.format(9)).isEqualTo("9s");
    assertThat(FlyTime.format(0)).isEqualTo("0s");
    assertThat(FlyTime.format(-5)).isEqualTo("0s");
  }

  @Test
  void theLastSecondStillReadsOneUntilTimeIsReallyUp() {
    assertThat(FlyTime.secondsLeft(10_000, 9_001)).isEqualTo(1);
    assertThat(FlyTime.secondsLeft(10_000, 9_000)).isEqualTo(1);
    assertThat(FlyTime.secondsLeft(10_000, 8_999)).isEqualTo(2);
    assertThat(FlyTime.secondsLeft(10_000, 10_000)).isZero();
    assertThat(FlyTime.secondsLeft(10_000, 12_000)).isZero();
  }
}
