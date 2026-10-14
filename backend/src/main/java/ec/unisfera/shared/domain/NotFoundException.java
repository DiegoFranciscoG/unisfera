package ec.unisfera.shared.domain;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super(what + " no existe");
    }
}
