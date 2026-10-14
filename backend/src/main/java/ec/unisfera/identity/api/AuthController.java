package ec.unisfera.identity.api;

import ec.unisfera.identity.application.AuthService;
import ec.unisfera.identity.security.AuthenticatedOnly;
import ec.unisfera.identity.security.CurrentUser;
import ec.unisfera.identity.security.PermissionResolver;
import ec.unisfera.identity.security.PublicEndpoint;
import ec.unisfera.identity.security.SecurityUtils;
import ec.unisfera.identity.application.PersonService;
import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.ForbiddenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "unisfera_rt";
    private static final String COOKIE_PATH = "/api/auth";

    public record LoginRequest(@NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9]+$") String username,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record ChallengePasswordRequest(@NotBlank String challengeToken, @NotBlank @Size(max = 128) String newPassword) {
    }

    public record MfaRequest(@NotBlank String challengeToken, @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    public record ForgotRequest(@NotBlank @Size(max = 20) String username) {
    }

    public record ResetRequest(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 128) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 128) String newPassword) {
    }

    public record AuthResponse(String status, String accessToken, long expiresIn, String challengeToken, String otpauthUri,
                               String mfaSecret, AuthService.UserSummary user) {
    }

    public record MeResponse(UUID userId, String username, String displayName, List<String> roles, Set<String> permissions) {
    }

    private final AuthService auth;
    private final AppProperties props;
    private final PermissionResolver permissions;
    private final PersonService persons;

    public AuthController(AuthService auth, AppProperties props, PermissionResolver permissions, PersonService persons) {
        this.auth = auth;
        this.props = props;
        this.permissions = permissions;
        this.persons = persons;
    }

    @Operation(summary = "Ingreso con cédula o pasaporte y contraseña")
    @PublicEndpoint
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest body) {
        return respond(auth.login(body.username(), body.password()));
    }

    @Operation(summary = "Cambio obligatorio de la contraseña temporal o caducada")
    @PublicEndpoint
    @PostMapping("/password/change-required")
    public ResponseEntity<AuthResponse> changeRequired(@Valid @RequestBody ChallengePasswordRequest body) {
        return respond(auth.changePasswordWithChallenge(body.challengeToken(), body.newPassword()));
    }

    @Operation(summary = "Verificación del segundo factor (TOTP)")
    @PublicEndpoint
    @PostMapping("/mfa/verify")
    public ResponseEntity<AuthResponse> mfa(@Valid @RequestBody MfaRequest body) {
        return respond(auth.verifyMfa(body.challengeToken(), body.code()));
    }

    @Operation(summary = "Rotación del token de refresco (cookie HttpOnly)")
    @PublicEndpoint
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                                HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !props.corsAllowedOrigins().contains(origin)) {
            throw new ForbiddenException("refresh-origin");
        }
        return respond(auth.refresh(refreshToken));
    }

    @PublicEndpoint
    @PostMapping("/password/forgot")
    public ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody ForgotRequest body) {
        auth.forgotPassword(body.username());
        return ResponseEntity.accepted().body(Map.of("message",
                "Si la cuenta tiene un correo institucional verificado, recibirá un enlace para restablecer la contraseña."));
    }

    @PublicEndpoint
    @PostMapping("/password/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest body) {
        auth.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @AuthenticatedOnly
    @PostMapping("/password/change")
    public ResponseEntity<Void> change(@Valid @RequestBody ChangePasswordRequest body) {
        auth.changePassword(SecurityUtils.requireUser().userId(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @AuthenticatedOnly
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        auth.logout(SecurityUtils.requireUser().sessionId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @Operation(summary = "Cerrar sesión en todos los dispositivos")
    @AuthenticatedOnly
    @PostMapping("/logout-all")
    public ResponseEntity<Map<String, Integer>> logoutAll() {
        int closed = auth.logoutAll(SecurityUtils.requireUser().userId());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).body(Map.of("closedSessions", closed));
    }

    @AuthenticatedOnly
    @GetMapping("/me")
    public MeResponse me() {
        CurrentUser user = SecurityUtils.requireUser();
        var person = persons.get(user.personId());
        return new MeResponse(user.userId(), user.username(), person.firstNames() + " " + person.lastNames(),
                List.copyOf(user.roles()), permissions.permissionsFor(user.roles()));
    }

    private ResponseEntity<AuthResponse> respond(AuthService.AuthOutcome outcome) {
        AuthResponse body = new AuthResponse(outcome.status().name(), outcome.accessToken(), outcome.expiresIn(),
                outcome.challengeToken(), outcome.otpauthUri(), outcome.mfaSecret(), outcome.user());
        if (outcome.refreshToken() == null) {
            return ResponseEntity.ok(body);
        }
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, outcome.refreshToken())
                .httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path(COOKIE_PATH)
                .maxAge(outcome.refreshTtl()).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(props.cookieSecure()).sameSite("Strict")
                .path(COOKIE_PATH).maxAge(Duration.ZERO).build();
    }
}
