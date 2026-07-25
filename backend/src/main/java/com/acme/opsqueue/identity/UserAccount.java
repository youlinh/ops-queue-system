package com.acme.opsqueue.identity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "username", nullable = false, length = 64, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_name", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<RoleName> roles = EnumSet.noneOf(RoleName.class);

    protected UserAccount() {
    }

    private UserAccount(
            String username,
            String displayName,
            String passwordHash,
            Set<RoleName> roles,
            boolean mustChangePassword) {
        this.id = UUID.randomUUID();
        this.username = normalizeUsername(username);
        this.displayName = displayName.trim();
        this.passwordHash = passwordHash;
        this.roles = copyRoles(roles);
        this.mustChangePassword = mustChangePassword;
        this.enabled = true;
    }

    public static UserAccount create(
            String username,
            String displayName,
            String passwordHash,
            Set<RoleName> roles,
            boolean mustChangePassword) {
        return new UserAccount(
                username, displayName, passwordHash, roles, mustChangePassword);
    }

    public boolean hasRole(RoleName role) {
        return roles.contains(role);
    }

    public void disable() {
        enabled = false;
    }

    public void resetPassword(String hash) {
        passwordHash = hash;
        mustChangePassword = true;
    }

    public void changePassword(String hash) {
        passwordHash = hash;
        mustChangePassword = false;
    }

    public void replaceRoles(Set<RoleName> newRoles) {
        roles.clear();
        roles.addAll(copyRoles(newRoles));
    }

    public void recordLogin(Instant loginAt) {
        lastLoginAt = loginAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID getId() {
        return id;
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant lastLoginAt() {
        return lastLoginAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Set<RoleName> roles() {
        return Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<RoleName> copyRoles(Set<RoleName> source) {
        if (source.isEmpty()) {
            return EnumSet.noneOf(RoleName.class);
        }
        return EnumSet.copyOf(source);
    }
}
