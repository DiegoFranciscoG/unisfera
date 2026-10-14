package ec.unisfera.shared.web;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Contexto de la petición en curso (request-id, IP, actor y proceso). La transacción lo
 * copia a la sesión de PostgreSQL para que la auditoría de la base sepa quién hizo qué.
 */
public final class RequestContext {

    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    private static final class State {
        private final String requestId;
        private final String ip;
        private final String userAgent;
        private UUID actorId;
        private String process;

        private State(String requestId, String ip, String userAgent) {
            this.requestId = requestId;
            this.ip = ip;
            this.userAgent = userAgent;
        }
    }

    public static void open(String requestId, String ip, String userAgent) {
        HOLDER.set(new State(requestId, ip, userAgent));
    }

    public static void close() {
        HOLDER.remove();
    }

    public static void setActor(UUID actorId) {
        State s = HOLDER.get();
        if (s != null) {
            s.actorId = actorId;
        }
    }

    public static Optional<UUID> actorId() {
        return Optional.ofNullable(HOLDER.get()).map(s -> s.actorId);
    }

    public static String requestId() {
        State s = HOLDER.get();
        return s == null ? null : s.requestId;
    }

    public static String ip() {
        State s = HOLDER.get();
        return s == null ? null : s.ip;
    }

    public static String userAgent() {
        State s = HOLDER.get();
        return s == null ? null : s.userAgent;
    }

    public static String process() {
        State s = HOLDER.get();
        return s == null ? null : s.process;
    }

    /** Ejecuta en segundo plano o en un proceso explícito con un actor conocido. */
    public static <T> T runAs(UUID actorId, String process, Supplier<T> work) {
        State previous = HOLDER.get();
        State state = new State(previous == null ? "job-" + UUID.randomUUID() : previous.requestId,
                previous == null ? null : previous.ip, previous == null ? null : previous.userAgent);
        state.actorId = actorId;
        state.process = process;
        HOLDER.set(state);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
