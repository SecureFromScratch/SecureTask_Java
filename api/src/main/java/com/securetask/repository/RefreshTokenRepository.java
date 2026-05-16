package com.securetask.repository;

import com.securetask.entity.RefreshToken;
import com.securetask.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void deleteByUser(User user);
    void deleteByExpiresAtBefore(Instant threshold);
}
