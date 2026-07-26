package com.acme.opsqueue.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtCookieService jwtCookies,
            IdentityService identities,
            CookieCsrfTokenRepository csrfRepository) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/auth/login"))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeJsonError(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeJsonError(
                                        response,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN",
                                        "Access is forbidden")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/api/auth/login",
                                "/api/auth/csrf")
                        .permitAll()
                        .requestMatchers("/api/admin/**")
                        .hasRole(RoleName.LEADER.name())
                        .requestMatchers("/api/rosters/**")
                        .hasRole(RoleName.LEADER.name())
                        .requestMatchers(HttpMethod.POST, "/api/tasks")
                        .hasRole(RoleName.DEVELOPER.name())
                        .requestMatchers("/api/tasks/*/call", "/api/tasks/*/complete")
                        .authenticated()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtCookies, identities),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new ForcedPasswordChangeFilter(),
                        JwtAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

    private static void writeJsonError(
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private static final class JwtAuthenticationFilter extends OncePerRequestFilter {
        private final JwtCookieService jwtCookies;
        private final IdentityService identities;

        private JwtAuthenticationFilter(
                JwtCookieService jwtCookies, IdentityService identities) {
            this.jwtCookies = jwtCookies;
            this.identities = identities;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            jwtCookies.readUserId(request).ifPresent(userId -> {
                try {
                    UserAccount account = identities.requireEnabled(userId);
                    CurrentUser currentUser = identities.toCurrentUser(account);
                    List<SimpleGrantedAuthority> authorities = currentUser.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                            .toList();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    currentUser, null, authorities));
                } catch (IdentityService.LoginRejectedException ignored) {
                    SecurityContextHolder.clearContext();
                }
            });
            filterChain.doFilter(request, response);
        }
    }

    private static final class ForcedPasswordChangeFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            Authentication authentication =
                    SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.getPrincipal() instanceof CurrentUser currentUser
                    && currentUser.mustChangePassword()
                    && request.getRequestURI().startsWith("/api/")
                    && !isPasswordChangeExempt(request.getRequestURI())) {
                writeJsonError(
                        response,
                        HttpServletResponse.SC_FORBIDDEN,
                        "PASSWORD_CHANGE_REQUIRED",
                        "Password change is required");
                return;
            }
            filterChain.doFilter(request, response);
        }

        private boolean isPasswordChangeExempt(String uri) {
            return uri.equals("/api/auth/me")
                    || uri.equals("/api/auth/change-password")
                    || uri.equals("/api/auth/logout")
                    || uri.equals("/api/auth/csrf");
        }
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken)
                    request.getAttribute("_csrf");
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
