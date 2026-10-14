package ec.unisfera.identity.application;

import ec.unisfera.shared.domain.BusinessRuleException;

/** Fallo de autenticación: responde 401 con un mensaje que no revela si el usuario existe (SEC-02). */
public class AuthenticationFailedException extends BusinessRuleException {

    public static final String GENERIC = "Usuario o contraseña incorrectos, o la cuenta está bloqueada temporalmente";

    public AuthenticationFailedException(String code, String message) {
        super(code, message);
    }

    public static AuthenticationFailedException generic() {
        return new AuthenticationFailedException("SEC-02", GENERIC);
    }
}
