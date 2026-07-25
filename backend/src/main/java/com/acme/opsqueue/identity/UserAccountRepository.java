package com.acme.opsqueue.identity;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
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
            where user.enabled = true and role = :role
            """)
    boolean existsByEnabledRole(@Param("role") RoleName role);

    @Query("""
            select distinct user
            from UserAccount user join user.roles role
            where user.enabled = true and role = :role
            order by user.id
            """)
    List<UserAccount> findEnabledByRole(@Param("role") RoleName role);

    List<UserAccount> findByUsernameIn(Collection<String> usernames);

    @Query("select user from UserAccount user where user.id in :ids")
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<UserAccount> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);

    @Query(
            value = """
                    select guard_name
                    from identity_guards
                    where guard_name = 'enabled-leader'
                    for update
                    """,
            nativeQuery = true)
    String lockEnabledLeaderGuard();
}
