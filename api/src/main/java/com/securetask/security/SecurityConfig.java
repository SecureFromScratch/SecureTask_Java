package com.securetask.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final SecureTaskUserDetailsService userDetailsService;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    @Value("${bff.service-token:}")
    private String bffServiceToken;

    public SecurityConfig(SecureTaskUserDetailsService userDetailsService,
                          JwtDecoder jwtDecoder,
                          JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.userDetailsService = userDetailsService;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    /**
     * Argon2 is chosen over BCrypt because it is the winner of the Password Hashing
     * Competition and is designed to be resistant to GPU-based brute-force attacks.
     * Parameters: saltLength=16, hashLength=32, parallelism=1, memory=65536, iterations=3.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // CSRF: cookie-based repository so JS can read the token and send it as a header.
        // Secure flag omitted here so local HTTP dev works; enable it in production.
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(requestHandler)
                // JWT token endpoints are stateless — no session, no CSRF risk.
                .ignoringRequestMatchers("/api/auth/token", "/api/auth/refresh", "/api/auth/revoke")
                // Bearer-authenticated requests cannot be forged by a cross-origin form
                // (forms cannot set the Authorization header), so they are CSRF-safe.
                .ignoringRequestMatchers(request ->
                    StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION)) &&
                    request.getHeader(HttpHeaders.AUTHORIZATION).startsWith("Bearer "))
                // BFF service token: trusted server-to-server calls (e.g. /api/register)
                // that arrive without a Bearer token but still originate from the BFF.
                .ignoringRequestMatchers(request ->
                    StringUtils.hasText(bffServiceToken) &&
                    bffServiceToken.equals(request.getHeader("X-BFF-Service-Token")))
            )
            // Ensure the XSRF-TOKEN cookie is written on every response so the BFF
            // always has a fresh token to forward to the browser.
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            // Pure REST API — no HTTP sessions. All auth is via JWT Bearer tokens.
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/register",
                    "/api/auth/token",
                    "/api/auth/refresh",
                    "/api/auth/revoke"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter)
                )
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"" + ex.getMessage() + "\"}");
                })
            )
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    request -> request.getRequestURI().startsWith("/api/")
                )
            );

        return http.build();
    }
}
