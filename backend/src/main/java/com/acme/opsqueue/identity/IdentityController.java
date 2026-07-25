package com.acme.opsqueue.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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

@RestController
@RequestMapping("/api")
public class IdentityController {
    private final IdentityService identities;
    private final JwtCookieService jwtCookies;
    private final CookieCsrfTokenRepository csrfTokens;

    public IdentityController(
            IdentityService identities,
            JwtCookieService jwtCookies,
            CookieCsrfTokenRepository csrfTokens) {
        this.identities = identities;
        this.jwtCookies = jwtCookies;
        this.csrfTokens = csrfTokens;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            CurrentUser currentUser = identities.authenticate(
                    request.username(), request.password(), servletRequest.getRemoteAddr());
            jwtCookies.issue(servletResponse, currentUser.id());
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
            HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokens.generateToken(request);
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
    public ResponseEntity<AccountView> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        UserAccount created = identities.create(
                request.username(),
                request.displayName(),
                request.initialPassword(),
                request.roles());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountView.from(created));
    }

    @PostMapping("/admin/users/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        identities.disable(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/users/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request) {
        identities.resetPassword(id, request.initialPassword());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/admin/users/{id}/roles")
    public AccountView replaceRoles(
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceRolesRequest request) {
        return AccountView.from(identities.replaceRoles(id, request.roles()));
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 256) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(min = 12, max = 256) String newPassword) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Size(min = 12, max = 256) String initialPassword,
            @NotEmpty Set<RoleName> roles) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 12, max = 256) String initialPassword) {
    }

    public record ReplaceRolesRequest(@NotEmpty Set<RoleName> roles) {
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
