package net.watones.novagems.purchaselog;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.watones.novagems.config.RuntimeConfig;
import net.watones.novagems.util.Formatters;

/**
 * Posts shop purchases to a Discord channel as embeds. Best effort by design: it only ever reads
 * what already happened, so a slow, rate-limited or missing Discord can delay or drop a log line
 * but never a purchase.
 *
 * <p>One daemon worker owns the HTTP calls. Events that pile up while it is busy are sent together
 * (Discord accepts several embeds per message), and a rate-limit answer is honoured by waiting.
 */
public final class PurchaseLogService implements AutoCloseable {
  private static final int QUEUE_CAPACITY = 256;
  /**
   * Discord allows ten embeds but only 6000 characters across all of them. Every text is clipped
   * after escaping, which bounds one embed at about 1000, so five always fit.
   */
  static final int MAX_EMBEDS_PER_MESSAGE = 5;
  private static final int MAX_ATTEMPTS = 3;
  /** Being told to wait is not a failure: it gets its own, larger budget. */
  private static final int MAX_RATE_LIMIT_WAITS = 6;
  private static final long MAX_RETRY_WAIT_MILLIS = 60_000;
  private static final long DEFAULT_PACING_MILLIS = 500;
  private static final long WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
  private static final long CLOSE_DRAIN_MILLIS = 1_500;

  private static final int GREEN = 0x57F287;
  private static final int RED = 0xED4245;
  private static final int YELLOW = 0xFEE75C;

  private final BlockingQueue<PurchaseEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final HttpClient client;
  private final Consumer<String> warningLog;
  private final long pacingMillis;
  private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE);
  private final Thread worker;
  private volatile RuntimeConfig.PurchaseLogSettings settings;
  private volatile boolean running = true;

  public PurchaseLogService(
      RuntimeConfig.PurchaseLogSettings settings, Consumer<String> warningLog) {
    this(settings, warningLog, DEFAULT_PACING_MILLIS);
  }

  PurchaseLogService(
      RuntimeConfig.PurchaseLogSettings settings, Consumer<String> warningLog, long pacingMillis) {
    this.settings = settings;
    this.warningLog = warningLog;
    this.pacingMillis = pacingMillis;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    this.worker =
        Thread.ofPlatform().daemon(true).name("NovaGems-PurchaseLog").unstarted(this::run);
    worker.start();
  }

  public void reconfigure(RuntimeConfig.PurchaseLogSettings next) {
    settings = next;
  }

  /** Never blocks and never throws: callable from the server thread and from storage callbacks. */
  public boolean record(PurchaseEvent event) {
    RuntimeConfig.PurchaseLogSettings active = settings;
    if (!running || !active.active() || !active.wants(event.kind())) return false;
    if (queue.offer(event)) return true;
    warnRateLimited("Cola del registro de compras llena; se descartó una entrada");
    return false;
  }

  public boolean enabled() {
    return settings.active();
  }

  public int queueSize() {
    return queue.size();
  }

  public int queueCapacity() {
    return QUEUE_CAPACITY;
  }

  private void run() {
    try {
      while (running || !queue.isEmpty()) {
        PurchaseEvent first = queue.poll(250, TimeUnit.MILLISECONDS);
        if (first == null) continue;
        List<PurchaseEvent> batch = new ArrayList<>(MAX_EMBEDS_PER_MESSAGE);
        batch.add(first);
        queue.drainTo(batch, MAX_EMBEDS_PER_MESSAGE - 1);
        try {
          send(batch);
        } catch (RuntimeException failure) {
          // Whatever went wrong with this batch, the worker must outlive it: a dead worker
          // would silently end the log until the next restart. Class name only, never the URL.
          warnRateLimited("Fallo inesperado enviando el registro de compras: "
              + failure.getClass().getSimpleName());
        }
        // Stay well inside Discord's per-webhook limit instead of discovering it by being refused.
        Thread.sleep(pacingMillis);
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private void send(List<PurchaseEvent> batch) throws InterruptedException {
    RuntimeConfig.PurchaseLogSettings active = settings;
    if (!active.active()) return;
    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(URI.create(active.webhookUrl()))
              .timeout(Duration.ofMillis(active.timeoutMillis()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload(batch, active)))
              .build();
    } catch (RuntimeException invalid) {
      warnRateLimited("El webhook del registro de compras no es una URL válida");
      return;
    }
    int rateLimitWaits = 0;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      long waitMillis = 1_000;
      try {
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        if (status == 429) {
          if (++rateLimitWaits > MAX_RATE_LIMIT_WAITS) {
            warnRateLimited("Discord sigue limitando el registro de compras; se descartaron "
                + batch.size() + " entrada(s)");
            return;
          }
          Thread.sleep(
              retryAfterMillis(response.headers().firstValue("Retry-After").orElse(null)));
          attempt--;
          continue;
        } else if (status < 500) {
          // The request itself is wrong (deleted webhook, bad payload): retrying cannot help.
          warnRateLimited("Discord rechazó el registro de compras (HTTP " + status + ")");
          return;
        }
      } catch (java.io.IOException failure) {
        if (attempt == MAX_ATTEMPTS) {
          warnRateLimited("No se pudo enviar el registro de compras a Discord: "
              + failure.getClass().getSimpleName());
          return;
        }
      }
      if (attempt == MAX_ATTEMPTS) {
        warnRateLimited("Discord no aceptó el registro de compras tras " + MAX_ATTEMPTS
            + " intentos; se descartaron " + batch.size() + " entrada(s)");
        return;
      }
      Thread.sleep(waitMillis);
    }
  }

  /** Discord sends seconds, possibly fractional. Anything unreadable falls back to one second. */
  static long retryAfterMillis(String header) {
    if (header == null) return 1_000;
    try {
      double seconds = Double.parseDouble(header.trim());
      if (!Double.isFinite(seconds) || seconds < 0) return 1_000;
      return Math.min(MAX_RETRY_WAIT_MILLIS, Math.max(100, (long) Math.ceil(seconds * 1_000)));
    } catch (NumberFormatException unreadable) {
      return 1_000;
    }
  }

  static String payload(List<PurchaseEvent> batch, RuntimeConfig.PurchaseLogSettings settings) {
    StringBuilder json = new StringBuilder(512 * batch.size());
    json.append("{\"username\":\"NovaGems\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[");
    for (int index = 0; index < batch.size(); index++) {
      if (index > 0) json.append(',');
      embed(json, batch.get(index), settings);
    }
    return json.append("]}").toString();
  }

  private static void embed(
      StringBuilder json, PurchaseEvent event, RuntimeConfig.PurchaseLogSettings settings) {
    String player = text(event.playerName(), 64);
    String reward = text(event.rewardName(), 120);
    String title;
    String description;
    int color;
    switch (event.kind()) {
      case DELIVERED -> {
        title = "🛒 Compra entregada";
        description = "**" + player + "** canjeó **" + reward + "**";
        color = GREEN;
      }
      case REFUNDED -> {
        title = "↩️ Compra reembolsada";
        description = "No se pudo entregar **" + reward + "** a **" + player
            + "** y se le devolvieron las gemas.";
        color = RED;
      }
      default -> {
        title = "⚠️ Compra en revisión manual";
        description = "La entrega de **" + reward + "** a **" + player
            + "** no pudo confirmarse. Resuélvela con `/novagems review`.";
        color = YELLOW;
      }
    }
    json.append("{\"title\":\"").append(escape(title)).append("\",\"description\":\"")
        .append(escape(description)).append("\",\"color\":").append(color);
    String avatar = avatarUrl(settings.avatarUrl(), event);
    if (avatar != null) {
      json.append(",\"thumbnail\":{\"url\":\"").append(escape(avatar)).append("\"}");
    }
    json.append(",\"fields\":[");
    field(json, "Jugador", player, true);
    json.append(',');
    field(json, "Canje", "`" + code(clip(event.rewardId(), 80)) + "`", true);
    json.append(',');
    field(json, "Cantidad", "x" + Formatters.number(Math.max(1, event.quantity())), true);
    json.append(',');
    field(json, event.kind() == PurchaseEvent.Kind.REFUNDED ? "Reembolsado" : "Precio",
        Formatters.number(event.price()) + " gemas", true);
    if (event.balance() != PurchaseEvent.UNKNOWN_BALANCE) {
      json.append(',');
      field(json, "Saldo", Formatters.number(event.balance()) + " gemas", true);
    }
    if (event.detail() != null && !event.detail().isBlank()) {
      json.append(',');
      field(json, "Motivo", text(event.detail(), 300), false);
    }
    json.append(',');
    field(json, "Operación", "`" + event.operationId() + "`", false);
    json.append("],\"footer\":{\"text\":\"").append(escape(footer(settings.serverName())))
        .append("\"},\"timestamp\":\"").append(event.at()).append("\"}");
  }

  private static void field(StringBuilder json, String name, String value, boolean inline) {
    json.append("{\"name\":\"").append(escape(name)).append("\",\"value\":\"")
        .append(escape(value)).append("\",\"inline\":").append(inline).append('}');
  }

  private static String footer(String serverName) {
    return serverName == null || serverName.isBlank()
        ? "NovaGems" : "NovaGems · " + clip(serverName.trim(), 64);
  }

  /** Null when the template is unset or would not be a well-formed https image URL. */
  static String avatarUrl(String template, PurchaseEvent event) {
    return avatarUrl(template, event.playerId().toString(), event.playerName());
  }

  /** Also used to validate the configured template against a sample player. */
  public static String avatarUrl(String template, String uuid, String name) {
    if (template == null || template.isBlank()) return null;
    // URLEncoder is for form data, where a space is '+'; in a URL path it has to be %20.
    String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    String url = template.trim().replace("<uuid>", uuid).replace("<name>", encodedName);
    if (!url.startsWith("https://") || url.length() > 512) return null;
    try {
      URI parsed = URI.create(url);
      return parsed.getHost() == null || parsed.getHost().isBlank() ? null : url;
    } catch (IllegalArgumentException malformed) {
      // A thumbnail Discord cannot parse gets the whole message refused; leave it out instead.
      return null;
    }
  }

  /** Escaped for display, then clipped: escaping can double the length, so the order matters. */
  private static String text(String value, int length) {
    return clip(markdown(value), length);
  }

  static String clip(String value, int length) {
    if (value.length() <= length) return value;
    int end = length - 1;
    // Never keep half of a surrogate pair, nor a backslash that lost the character it escaped.
    if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
    int backslashes = 0;
    while (end - backslashes > 0 && value.charAt(end - backslashes - 1) == '\\') backslashes++;
    if (backslashes % 2 == 1) end--;
    return value.substring(0, end) + "…";
  }

  /** Player and reward names are data, not formatting: an underscore must not start italics. */
  static String markdown(String value) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      // A line break inside **bold** would end the emphasis and spill the rest as plain text.
      if (Character.isWhitespace(character) || Character.isISOControl(character)) character = ' ';
      if ("\\*_~`|>#[]()".indexOf(character) >= 0) out.append('\\');
      out.append(character);
    }
    return out.toString();
  }

  /** Inside a code span nothing is formatting except the backtick that would close it. */
  private static String code(String value) {
    return value.replace('`', '\'');
  }

  static String escape(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
          else escaped.append(character);
        }
      }
    }
    return escaped.toString();
  }

  private void warnRateLimited(String message) {
    long now = System.nanoTime();
    long previous = lastWarning.get();
    if ((previous == Long.MIN_VALUE || now - previous >= WARNING_INTERVAL_NANOS)
        && lastWarning.compareAndSet(previous, now)) warningLog.accept(message);
  }

  /** Lets what is already queued go out, briefly, then stops whatever is left. */
  @Override
  public void close() {
    running = false;
    try {
      worker.join(CLOSE_DRAIN_MILLIS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) worker.interrupt();
    // Otherwise its selector thread and pooled connection outlive the plugin.
    client.shutdownNow();
  }
}
