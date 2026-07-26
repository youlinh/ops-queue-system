package com.acme.opsqueue.identity;

import com.acme.opsqueue.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api")
public class IdentityController {
    private final IdentityService identities;
    private final JwtCookieService jwtCookies;
    private final ClientIpResolver clientIps;
    private final CookieCsrfTokenRepository csrfTokens;
    private final AuditService audits;
    private final Clock clock;

    public IdentityController(
            IdentityService identities,
            JwtCookieService jwtCookies,
            ClientIpResolver clientIps,
            CookieCsrfTokenRepository csrfTokens,
            AuditService audits,
            Clock clock) {
        this.identities = identities;
        this.jwtCookies = jwtCookies;
        this.clientIps = clientIps;
        this.csrfTokens = csrfTokens;
        this.audits = audits;
        this.clock = clock;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            String sourceIp = clientIps.resolve(servletRequest);
            CurrentUser currentUser = identities.authenticate(
                    request.username(), request.password(), sourceIp);
            jwtCookies.issue(servletResponse, currentUser.id());
            audits.record(
                    currentUser.id(), "LOGIN_SUCCESS", "USER", currentUser.id(),
                    Map.of(), Map.of("username", currentUser.username()),
                    sourceIp, clock.instant());
            return ResponseEntity.ok(currentUser);
        } catch (IdentityService.LoginRateLimitedException exception) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        } catch (IdentityService.LoginRejectedException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        jwtCookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public CurrentUser me(@AuthenticationPrincipal CurrentUser currentUser) {
        return currentUser;
    }

    @GetMapping("/auth/csrf")
    public CsrfResponse csrf(
            CsrfToken token,
            HttpServletRequest request,
            HttpServletResponse response) {
        csrfTokens.saveToken(token, request, response);
        return new CsrfResponse(token.getToken());
    }

    @PostMapping("/auth/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ChangePasswordRequest request) {
        identities.changeOwnPassword(
                currentUser.id(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/users")
    @Transactional
    public ResponseEntity<AccountView> createUser(
            @AuthenticationPrincipal CurrentUser leader,
            @Valid @RequestBody CreateUserRequest request) {
        UserAccount created = identities.create(
                request.username(),
                request.displayName(),
                request.initialPassword(),
                request.roles());
        audits.recordCurrentRequest(
                leader.id(), "ACCOUNT_CREATED", "USER", created.id(), Map.of(),
                Map.of(
                        "username", created.username(),
                        "enabled", created.enabled(),
                        "roles", roleNames(created.roles())),
                clock.instant());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountView.from(created));
    }

    @PostMapping("/admin/users/{id}/disable")
    @Transactional
    public ResponseEntity<Void> disable(
            @PathVariable UUID id,
            @AuthenticationPrincipal CurrentUser leader) {
        identities.disable(id);
        audits.recordCurrentRequest(
                leader.id(), "ACCOUNT_DISABLED", "USER", id,
                Map.of("enabled", true), Map.of("enabled", false), clock.instant());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/users/{id}/reset-password")
    @Transactional
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @AuthenticationPrincipal CurrentUser leader,
            @Valid @RequestBody ResetPasswordRequest request) {
        identities.resetPassword(id, request.initialPassword());
        audits.recordCurrentRequest(
                leader.id(), "ACCOUNT_PASSWORD_RESET", "USER", id, Map.of(),
                Map.of("passwordReset", true), clock.instant());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/admin/users/{id}/roles")
    @Transactional
    public AccountView replaceRoles(
            @PathVariable UUID id,
            @AuthenticationPrincipal CurrentUser leader,
            @Valid @RequestBody ReplaceRolesRequest request) {
        UserAccount updated = identities.replaceRoles(id, request.roles());
        audits.recordCurrentRequest(
                leader.id(), "ACCOUNT_ROLES_CHANGED", "USER", id, Map.of(),
                Map.of("roles", roleNames(updated.roles())), clock.instant());
        return AccountView.from(updated);
    }

    private List<String> roleNames(Set<RoleName> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Utf8ByteLength(max = 72) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Utf8ByteLength(max = 72) String currentPassword,
            @NotBlank @Size(min = 12) @Utf8ByteLength(max = 72) String newPassword) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Size(min = 12) @Utf8ByteLength(max = 72) String initialPassword,
            @NotEmpty Set<@NotNull RoleName> roles) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 12) @Utf8ByteLength(max = 72) String initialPassword) {
    }

    public record ReplaceRolesRequest(@NotEmpty Set<@NotNull RoleName> roles) {
    }

    public record CsrfResponse(String token) {
    }

    public record AccountView(
            UUID id,
            String username,
            String displayName,
            Set<RoleName> roles,
            boolean enabled,
            boolean mustChangePassword) {

        static AccountView from(UserAccount account) {
            return new AccountView(
                    account.id(),
                    account.username(),
                    account.displayName(),
                    account.roles(),
                    account.enabled(),
                    account.mustChangePassword());
        }
    }
}
