package net.watones.novagems.purchaselog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.watones.novagems.config.RuntimeConfig;
import org.junit.jupiter.api.Test;

class PurchaseLogServiceTest {
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

  @Test
  void embedCarriesThePurchaseAndNeverLetsNamesBecomeFormattingOrMentions() {
    PurchaseEvent event = new PurchaseEvent(
        PurchaseEvent.Kind.DELIVERED, PLAYER, "x_Pro_x", "llave-voto", "Llave \"VOTO\" @everyone",
        5, 12_500, 87_500, OPERATION, Instant.parse("2026-10-13T12:00:00Z"), null);

    String json = PurchaseLogService.payload(List.of(event), settings("https://x/hook", "Survival"));

    assertThat(json)
        .contains("\"allowed_mentions\":{\"parse\":[]}")
        .contains("Compra entregada")
        .contains("x\\\\_Pro\\\\_x")
        .contains("Llave \\\"VOTO\\\" @everyone")
        .contains("`llave-voto`")
        .contains("x5")
        .contains("12,500 gemas")
        .contains("87,500 gemas")
        .contains(OPERATION.toString())
        .contains("NovaGems · Survival")
        .contains("\"timestamp\":\"2026-10-13T12:00:00Z\"")
        .contains("https://mc-heads.net/avatar/" + PLAYER + "/64")
        .doesNotContain("Motivo");
  }

  @Test
  void refundAndReviewEmbedsExplainWhatHappenedAndOmitAnUnknownBalance() {
    PurchaseEvent refund = new PurchaseEvent(
        PurchaseEvent.Kind.REFUNDED, PLAYER, "Steve", "item-totem", null, 9, 45,
        PurchaseEvent.UNKNOWN_BALANCE, OPERATION, Instant.EPOCH, "Inventario lleno");
    PurchaseEvent review = new PurchaseEvent(
        PurchaseEvent.Kind.MANUAL_REVIEW, PLAYER, "Steve", "rango-vip", "Rango VIP", 1, 7_500,
        10, OPERATION, Instant.EPOCH, "El comando de canje no fue aceptado");

    String json = PurchaseLogService.payload(List.of(refund, review), settings("https://x", ""));

    assertThat(json)
        .contains("Compra reembolsada", "Reembolsado", "Inventario lleno")
        .contains("Compra en revisión manual", "/novagems review")
        // A reward that no longer exists is shown by its id.
        .contains("**item-totem**")
        .contains("\"footer\":{\"text\":\"NovaGems\"}");
    assertThat(json.split("\"Saldo\"", -1)).hasSize(2);
  }

  @Test
  void avatarIsOmittedUnlessItIsAnHttpsUrl() {
    PurchaseEvent event = event(PurchaseEvent.Kind.DELIVERED, "Steve Jr");
    assertThat(PurchaseLogService.avatarUrl("", event)).isNull();
    assertThat(PurchaseLogService.avatarUrl("http://insecure/<uuid>", event)).isNull();
    assertThat(PurchaseLogService.avatarUrl("https://img/<name>.png", event))
        .isEqualTo("https://img/Steve%20Jr.png");
    assertThat(PurchaseLogService.avatarUrl("https://bad host/<uuid>", event)).isNull();
  }

  @Test
  void retryAfterIsReadInSecondsAndBounded() {
    assertThat(PurchaseLogService.retryAfterMillis("0.25")).isEqualTo(250);
    assertThat(PurchaseLogService.retryAfterMillis("2")).isEqualTo(2_000);
    assertThat(PurchaseLogService.retryAfterMillis("3600")).isEqualTo(60_000);
    assertThat(PurchaseLogService.retryAfterMillis("soon")).isEqualTo(1_000);
    assertThat(PurchaseLogService.retryAfterMillis(null)).isEqualTo(1_000);
  }

  @Test
  void aBurstIsGroupedWithinTheEmbedCapAndARateLimitIsWaitedOutNotDropped() throws Exception {
    List<String> accepted = new CopyOnWriteArrayList<>();
    List<Long> arrivals = new CopyOnWriteArrayList<>();
    CountDownLatch firstArrived = new CountDownLatch(1);
    CountDownLatch burstQueued = new CountDownLatch(1);
    CountDownLatch allEmbeds = new CountDownLatch(7);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      arrivals.add(System.nanoTime());
      if (arrivals.size() == 1) {
        // Hold the worker here until the rest of the burst is queued, then refuse this one.
        firstArrived.countDown();
        try {
          burstQueued.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        arrivals.set(0, System.nanoTime());
        exchange.getResponseHeaders().add("Retry-After", "0.2");
        exchange.sendResponseHeaders(429, -1);
      } else {
        accepted.add(body);
        exchange.sendResponseHeaders(204, -1);
        for (int i = titles(body); i > 0; i--) allEmbeds.countDown();
      }
      exchange.close();
    });
    server.start();
    PurchaseLogService log = new PurchaseLogService(
        settings("http://127.0.0.1:" + server.getAddress().getPort() + "/hook", ""),
        ignored -> {}, 20);
    try {
      assertThat(log.record(event(PurchaseEvent.Kind.DELIVERED, "Player0"))).isTrue();
      assertThat(firstArrived.await(5, TimeUnit.SECONDS)).isTrue();
      for (int i = 1; i < 7; i++) {
        assertThat(log.record(event(PurchaseEvent.Kind.DELIVERED, "Player" + i))).isTrue();
      }
      burstQueued.countDown();
      assertThat(allEmbeds.await(10, TimeUnit.SECONDS)).isTrue();

      // The refused message again on its own, then five together, then the last one.
      assertThat(accepted).hasSize(3);
      assertThat(accepted).allSatisfy(body ->
          assertThat(titles(body)).isLessThanOrEqualTo(PurchaseLogService.MAX_EMBEDS_PER_MESSAGE));
      String everything = String.join("", accepted);
      for (int i = 0; i < 7; i++) {
        assertThat(everything.split("\\*\\*Player" + i + "\\*\\*", -1)).hasSize(2);
      }
      // The retry waited for the time Discord asked for instead of hammering it.
      assertThat(arrivals.get(1) - arrivals.get(0))
          .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(180));
    } finally {
      log.close();
      server.stop(0);
    }
  }

  @Test
  void anUnexpectedFailureWhileSendingDoesNotEndTheLogForGood() throws Exception {
    CountDownLatch delivered = new CountDownLatch(1);
    CountDownLatch warned = new CountDownLatch(1);
    List<String> warnings = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      delivered.countDown();
    });
    server.start();
    // A port no socket can have: the HTTP client rejects it with an unchecked exception.
    PurchaseLogService log = new PurchaseLogService(
        settings("https://discord.com:99999/api/webhooks/1/secret-token", ""),
        message -> {
          warnings.add(message);
          warned.countDown();
        }, 20);
    try {
      assertThat(log.record(event(PurchaseEvent.Kind.DELIVERED, "Steve"))).isTrue();
      assertThat(warned.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(String.join(" ", warnings)).doesNotContain("secret-token", "discord.com");

      log.reconfigure(settings("http://127.0.0.1:" + server.getAddress().getPort() + "/hook", ""));
      assertThat(log.record(event(PurchaseEvent.Kind.DELIVERED, "Alex"))).isTrue();
      assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      log.close();
      server.stop(0);
    }
  }

  @Test
  void textIsClippedAfterEscapingSoFiveWorstCaseEmbedsStayWithinDiscordsTotal() {
    String hostile = "_*~`|>#[]()".repeat(40);
    List<PurchaseEvent> batch = new java.util.ArrayList<>();
    for (int i = 0; i < PurchaseLogService.MAX_EMBEDS_PER_MESSAGE; i++) {
      batch.add(new PurchaseEvent(PurchaseEvent.Kind.MANUAL_REVIEW, PLAYER, hostile, hostile,
          hostile, 576, Long.MAX_VALUE, Long.MAX_VALUE, OPERATION, Instant.EPOCH, hostile));
    }
    String json = PurchaseLogService.payload(batch, settings("https://x", "S".repeat(200)));

    // Discord counts title, description, field names and values and footer text.
    java.util.regex.Matcher counted = java.util.regex.Pattern
        .compile("\"(?:title|description|name|value|text)\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        .matcher(json);
    int total = 0;
    while (counted.find()) total += counted.group(1).replaceAll("\\\\(.)", "$1").length();
    assertThat(total).isLessThan(6_000);
    assertThat(PurchaseLogService.clip("ab\\_cd", 4)).isEqualTo("ab…");
    assertThat(PurchaseLogService.clip("a😀b", 3)).isEqualTo("a…");
  }

  @Test
  void nothingIsQueuedWhenDisabledOrWhenThatKindOfEventIsSwitchedOff() {
    RuntimeConfig.PurchaseLogSettings off = new RuntimeConfig.PurchaseLogSettings(
        false, "https://x", "", "", 2000, true, true, true);
    RuntimeConfig.PurchaseLogSettings deliveredOnly = new RuntimeConfig.PurchaseLogSettings(
        true, "http://127.0.0.1:9/none", "", "", 2000, true, false, false);
    PurchaseLogService log = new PurchaseLogService(off, ignored -> {});
    try {
      assertThat(log.enabled()).isFalse();
      assertThat(log.record(event(PurchaseEvent.Kind.DELIVERED, "Steve"))).isFalse();
      log.reconfigure(deliveredOnly);
      assertThat(log.enabled()).isTrue();
      assertThat(log.record(event(PurchaseEvent.Kind.REFUNDED, "Steve"))).isFalse();
      assertThat(log.record(event(PurchaseEvent.Kind.MANUAL_REVIEW, "Steve"))).isFalse();
      assertThat(deliveredOnly.toString()).doesNotContain("127.0.0.1");
    } finally {
      log.close();
    }
  }

  private static int titles(String body) {
    return body.split("\"title\"", -1).length - 1;
  }

  private static PurchaseEvent event(PurchaseEvent.Kind kind, String player) {
    return new PurchaseEvent(kind, PLAYER, player, "llave-voto", "Llave Voto", 1, 2_500, 100,
        OPERATION, Instant.EPOCH, null);
  }

  private static RuntimeConfig.PurchaseLogSettings settings(String url, String server) {
    return new RuntimeConfig.PurchaseLogSettings(
        true, url, server, "https://mc-heads.net/avatar/<uuid>/64", 2000, true, true, true);
  }
}
