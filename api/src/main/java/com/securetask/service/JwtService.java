package com.securetask.service;

import com.securetask.dto.TokenResponse;
import com.securetask.entity.RefreshToken;
import com.securetask.entity.User;
import com.securetask.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${jwt.access-token-expiry:900}")
    private long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry:604800}")
    private long refreshTokenExpiry;

    public JwtService(JwtEncoder jwtEncoder, RefreshTokenRepository refreshTokenRepository) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public TokenResponse issueTokenPair(User user) {
        String accessToken = generateAccessToken(user);
        String rawRefreshToken = generateAndStoreRefreshToken(user);
        return new TokenResponse(accessToken, accessTokenExpiry, rawRefreshToken);
    }

    @Transactional
    public TokenResponse validateAndRotateRefresh(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.delete(stored);
            throw new UnauthorizedException("Refresh token expired");
        }
        User user = stored.getUser();
        refreshTokenRepository.delete(stored);
        return issueTokenPair(user);
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .ifPresent(refreshTokenRepository::delete);
    }

    private String generateAccessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("securetask")
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTokenExpiry))
                .claim("roles", List.of("ROLE_" + user.getRole().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String generateAndStoreRefreshToken(User user) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setTokenHash(sha256(raw));
        token.setUser(user);
        token.setExpiresAt(Instant.now().plusSeconds(refreshTokenExpiry));
        refreshTokenRepository.save(token);

        return raw;
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
