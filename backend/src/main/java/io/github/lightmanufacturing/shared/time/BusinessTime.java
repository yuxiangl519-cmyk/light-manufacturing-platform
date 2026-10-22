package io.github.lightmanufacturing.shared.time;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Thread-scoped historical import clock. No HTTP input can activate this context. */
public final class BusinessTime {
  private static final ThreadLocal<Instant> IMPORT_TIME = new ThreadLocal<>();
  private static final DateTimeFormatter SQL =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  private BusinessTime() {}

  public static Instant now() {
    Instant value = IMPORT_TIME.get();
    return value == null ? Instant.now() : value;
  }

  public static LocalDate today(ZoneId zone) {
    return now().atZone(zone).toLocalDate();
  }

  /** Formats only an Instant, never SQL or request text. */
  public static String sqlNow() {
    return IMPORT_TIME.get() == null ? "utc_timestamp(3)" : "TIMESTAMP '" + SQL.format(now()) + "'";
  }

  public static Scope at(Instant instant) {
    if (!Boolean.getBoolean("mfg.demo.import") || IMPORT_TIME.get() != null)
      throw new IllegalStateException("Historical clock requires an explicit local import process");
    Objects.requireNonNull(instant);
    if (instant.isBefore(Instant.parse("2000-01-01T00:00:00Z")))
      throw new IllegalArgumentException("Import date outside supported range");
    IMPORT_TIME.set(instant.truncatedTo(ChronoUnit.MILLIS));
    return new Scope(Thread.currentThread());
  }

  public static final class Scope implements AutoCloseable {
    private final Thread owner;
    private boolean closed;

    private Scope(Thread owner) {
      this.owner = owner;
    }

    @Override
    public void close() {
      if (Thread.currentThread() != owner)
        throw new IllegalStateException("Clock scope owner mismatch");
      if (!closed) {
        IMPORT_TIME.remove();
        closed = true;
      }
    }
  }
}
