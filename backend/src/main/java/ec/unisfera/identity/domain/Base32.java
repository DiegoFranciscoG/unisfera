package ec.unisfera.identity.domain;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Base32 (RFC 4648) sin relleno, el formato que usan las apps autenticadoras. */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS_PER_CHAR = 5;
    private static final int BYTE_BITS = 8;
    private static final int CHAR_MASK = 0x1F;
    private static final int BYTE_MASK = 0xFF;

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << BYTE_BITS) | (b & BYTE_MASK);
            bits += BYTE_BITS;
            while (bits >= BITS_PER_CHAR) {
                out.append(ALPHABET.charAt((buffer >> (bits - BITS_PER_CHAR)) & CHAR_MASK));
                bits -= BITS_PER_CHAR;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (BITS_PER_CHAR - bits)) & CHAR_MASK));
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase(Locale.ROOT).replace("=", "").toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Carácter Base32 inválido");
            }
            buffer = (buffer << BITS_PER_CHAR) | value;
            bits += BITS_PER_CHAR;
            if (bits >= BYTE_BITS) {
                out.write((buffer >> (bits - BYTE_BITS)) & BYTE_MASK);
                bits -= BYTE_BITS;
            }
        }
        return out.toByteArray();
    }
}
