package com.acme.opsqueue.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("""
            select case when count(user) > 0 then true else false end
            from UserAccount user join user.roles role
            where role = :role
            """)
    boolean existsByRolesContaining(@Param("role") RoleName role);
}
