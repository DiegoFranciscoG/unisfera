package ec.unisfera.identity.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP según RFC 6238 (HMAC-SHA1, pasos de 30 s, 6 dígitos), compatible con cualquier app
 * autenticadora. Función pura salvo la generación del secreto.
 */
public final class Totp {

    private static final String ALGORITHM = "HmacSHA1";
    private static final long STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MODULO = 1_000_000;
    private static final int SECRET_BYTES = 20;
    private static final int WINDOW = 1;
    private static final int OFFSET_MASK = 0x0F;
    private static final int TRUNCATE_MASK = 0x7FFF_FFFF;

    private Totp() {
    }

    public static String newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return Base32.encode(secret);
    }

    public static long step(Instant instant) {
        return instant.getEpochSecond() / STEP_SECONDS;
    }

    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(Base32.decode(base32Secret), ALGORITHM));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & OFFSET_MASK;
            int binary = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & TRUNCATE_MASK;
            return String.format("%0" + DIGITS + "d", binary % MODULO);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Devuelve el paso aceptado (para impedir reutilizar un código) o -1 si el código no es válido. */
    public static long verify(String base32Secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        long current = step(now);
        for (long s = current - WINDOW; s <= current + WINDOW; s++) {
            if (constantTimeEquals(code(base32Secret, s), code)) {
                return s;
            }
        }
        return -1;
    }

    public static String otpauthUri(String issuer, String account, String secret) {
        return "otpauth://totp/" + issuer + ":" + account + "?secret=" + secret + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
