package ec.unisfera.shared.domain;

/** Acceso a un recurso ajeno (otro estudiante, un paralelo de otro docente). Responde 403. */
public class ForbiddenException extends RuntimeException {

    private final String resource;

    public ForbiddenException(String resource) {
        super("No tiene acceso a este recurso");
        this.resource = resource;
    }

    public String resource() {
        return resource;
    }
}
