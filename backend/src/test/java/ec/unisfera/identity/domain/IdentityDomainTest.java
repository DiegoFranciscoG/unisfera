package ec.unisfera.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.unisfera.shared.domain.BusinessRuleException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityDomainTest {

    @Nested
    class Cedula {

        @Test
        void sec01_acceptsAValidCedula() {
            assertThat(CedulaValidator.isValid("1710034065")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"1710034066", "2510034065", "0060034065", "1760034062", "171003406", "17100340651", "17100340a5", ""})
        void sec01_rejectsInvalidOnes(String value) {
            assertThat(CedulaValidator.isValid(value)).isFalse();
        }

        @Test
        void sec01_null() {
            assertThat(CedulaValidator.isValid(null)).isFalse();
        }

        @Test
        void sec01_abroadSeriesSkipsTheThirdDigitRule() {
            String nine = "309000001";
            assertThat(CedulaValidator.isValid(nine + CedulaValidator.checkDigit(nine))).isTrue();
        }

        @Test
        void sec01_everyGeneratedCedulaWithItsCheckDigitIsValidAndAnyOtherDigitIsNot() {
            SplittableRandom random = new SplittableRandom(20261013);
            for (int i = 0; i < 2_000; i++) {
                String nine = String.format("%02d%d%06d", 1 + random.nextInt(24), random.nextInt(6), random.nextInt(1_000_000));
                int check = CedulaValidator.checkDigit(nine);
                assertThat(CedulaValidator.isValid(nine + check)).isTrue();
                assertThat(CedulaValidator.isValid(nine + ((check + 1) % 10))).isFalse();
            }
        }
    }

    @Nested
    class Passwords {

        @Test
        void sec01_rejectsShortPasswords() {
            assertThatThrownBy(() -> PasswordPolicy.validate("Corta-1", "0102030405", 12)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void sec01_cedulaIsNeverPartOfThePassword() {
            assertThatThrownBy(() -> PasswordPolicy.validate("MiClave-171003-segura", "1710034065", 12))
                    .hasMessageContaining("cédula");
        }

        @Test
        void sec01_rejectsCommonWordsAndRepetition() {
            assertThatThrownBy(() -> PasswordPolicy.validate("Unisfera-2026-ok", "0102030405", 12)).hasMessageContaining("común");
            assertThatThrownBy(() -> PasswordPolicy.validate("aaaaaaaaaaaaaaaa", "0102030405", 12)).hasMessageContaining("repetitiva");
        }

        @Test
        void sec01_acceptsAGoodPassphrase() {
            PasswordPolicy.validate("Río Tomebamba corre al amanecer", "0102030405", 12);
        }
    }

    @Nested
    class Lockout {

        @Test
        void sec03_noLockBeforeTheThreshold() {
            assertThat(LockoutPolicy.lockFor(4, 5, 30, 3600)).isEqualTo(Duration.ZERO);
        }

        @Test
        void sec03_lockDoublesAndIsCapped() {
            assertThat(LockoutPolicy.lockFor(5, 5, 30, 3600)).isEqualTo(Duration.ofSeconds(30));
            assertThat(LockoutPolicy.lockFor(6, 5, 30, 3600)).isEqualTo(Duration.ofSeconds(60));
            assertThat(LockoutPolicy.lockFor(8, 5, 30, 3600)).isEqualTo(Duration.ofSeconds(240));
            assertThat(LockoutPolicy.lockFor(40, 5, 30, 3600)).isEqualTo(Duration.ofSeconds(3600));
        }
    }

    @Nested
    class TotpRfc6238 {

        private final String rfcSecret = Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

        @Test
        void sec04_matchesTheRfcTestVectors() {
            // RFC 6238, apéndice B (SHA-1): los 6 últimos dígitos de 94287082, 07081804 y 14050471
            assertThat(Totp.code(rfcSecret, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
            assertThat(Totp.code(rfcSecret, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
            assertThat(Totp.code(rfcSecret, Totp.step(Instant.ofEpochSecond(1111111111)))).isEqualTo("050471");
        }

        @Test
        void sec04_acceptsOneStepOfDriftAndRejectsGarbage() {
            Instant now = Instant.ofEpochSecond(1_800_000_000L);
            String previous = Totp.code(rfcSecret, Totp.step(now) - 1);
            assertThat(Totp.verify(rfcSecret, previous, now)).isEqualTo(Totp.step(now) - 1);
            assertThat(Totp.verify(rfcSecret, "12345", now)).isEqualTo(-1);
            assertThat(Totp.verify(rfcSecret, Totp.code(rfcSecret, Totp.step(now) + 5), now)).isEqualTo(-1);
        }

        @Test
        void base32RoundTrip() {
            byte[] data = new byte[37];
            new SplittableRandom(7).nextBytes(data);
            assertThat(Base32.decode(Base32.encode(data))).isEqualTo(data);
            assertThat(Totp.newSecret()).hasSize(32);
            assertThat(Totp.otpauthUri("UNISFERA", "0102030405", "ABC")).startsWith("otpauth://totp/UNISFERA:0102030405?secret=ABC");
        }
    }
}
