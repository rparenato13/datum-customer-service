package br.com.datum.notification.observability;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationContext {
    public static final String HTTP_HEADER = "X-Correlation-Id";
    public static final String MESSAGE_HEADER = "x-correlation-id";
    private static final Pattern UUID_V4 = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private CorrelationContext() { }

    public static boolean valid(Object value) {
        return value instanceof String text && UUID_V4.matcher(text).matches();
    }

    public static String id() { return CURRENT.get() == null ? null : CURRENT.get().id; }
    public static UUID idOrNew() { return id() == null ? UUID.randomUUID() : UUID.fromString(id()); }
    public static Scope open(String value) { return new Scope(valid(value) ? value : UUID.randomUUID().toString()); }

    public static void use(String validId) {
        if (!valid(validId) || CURRENT.get() == null) throw new IllegalArgumentException("Correlacao invalida.");
        CURRENT.get().id = validId;
        MDC.put("correlationId", validId);
    }

    public static void database(long elapsedMs) {
        if (CURRENT.get() != null) {
            CURRENT.get().databaseMs += elapsedMs;
            CURRENT.get().queries++;
        }
    }

    public static long databaseMs() { return CURRENT.get() == null ? 0 : CURRENT.get().databaseMs; }
    public static long queries() { return CURRENT.get() == null ? 0 : CURRENT.get().queries; }

    private static final class State {
        String id;
        long databaseMs;
        long queries;
        State(String id) { this.id = id; }
    }

    public static final class Scope implements AutoCloseable {
        private final State previous = CURRENT.get();
        private final Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        private Scope(String id) {
            CURRENT.set(new State(id));
            MDC.put("correlationId", id);
        }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (previousMdc == null) MDC.clear(); else MDC.setContextMap(previousMdc);
        }
    }
}
