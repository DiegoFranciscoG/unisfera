package ec.unisfera.identity.security;

import ec.unisfera.shared.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Cifrado de datos sensibles (RN-23, secretos TOTP) con AES-256-GCM. El contexto (tabla.campo)
 * viaja como dato asociado: un texto cifrado no puede copiarse a otro campo sin detectarse.
 */
@Component
public class CryptoService {

    private static final String AES = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte FORMAT_VERSION = 1;
    private static final String HMAC = "HmacSHA256";

    private final SecretKeySpec key;
    private final SecretKeySpec signingKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(AppProperties props) {
        byte[] raw = Base64.getDecoder().decode(props.sensitiveDataKey());
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("SENSITIVE_DATA_KEY debe decodificar a exactamente 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(raw, AES);
        this.signingKey = new SecretKeySpec(props.actSigningKey().getBytes(StandardCharsets.UTF_8), HMAC);
    }

    public byte[] encrypt(String plain, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + encrypted.length).put(FORMAT_VERSION).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar", e);
        }
    }

    public String decrypt(byte[] payload, String context) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            if (buffer.get() != FORMAT_VERSION) {
                throw new IllegalStateException("Formato de cifrado desconocido");
            }
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar", e);
        }
    }

    /** Firma HMAC-SHA256 en hexadecimal (firma del docente sobre el hash del acta). */
    public String sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(signingKey);
            return java.util.HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar", e);
        }
    }
}
