package com.securetask.dto;

public class TokenResponse {

    private final String accessToken;
    private final String tokenType = "Bearer";
    private final long expiresIn;
    private final String refreshToken;

    public TokenResponse(String accessToken, long expiresIn, String refreshToken) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
        this.refreshToken = refreshToken;
    }

    public String getAccessToken() { return accessToken; }
    public String getTokenType() { return tokenType; }
    public long getExpiresIn() { return expiresIn; }
    public String getRefreshToken() { return refreshToken; }
}
