package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    Optional<UserEntity> findByUsernameIgnoreCase(String username);
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByEmailIgnoreCase(String email);
    boolean existsByUsername(String username);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    @Query("""
            select u from UserEntity u
             where lower(u.email) like lower(concat('%', :query, '%'))
                or lower(coalesce(u.fullName, u.username)) like lower(concat('%', :query, '%'))
             order by u.createdAt desc
            """)
    List<UserEntity> search(@Param("query") String query);
}
