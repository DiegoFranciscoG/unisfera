package ec.unisfera.finance.gateway;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Firma HMAC-SHA256 de los webhooks; la comparación es en tiempo constante. */
public final class WebhookSigner {

    private static final String HMAC = "HmacSHA256";

    private WebhookSigner() {
    }

    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String secret, String body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
