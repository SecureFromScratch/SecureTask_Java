package com.securetask.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
// Enables @PreAuthorize and @PostAuthorize on service and controller methods.
@EnableMethodSecurity
public class SecurityConfig {

    private final SecureTaskUserDetailsService userDetailsService;

    public SecurityConfig(SecureTaskUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
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
        // CSRF: Use the cookie-based repository so the JavaScript client can read
        // the token from the XSRF-TOKEN cookie and send it as a request header.
        // The full CSRF walkthrough is in Lab 03; this is the minimal working setup.
        // Secure flag is omitted here so local HTTP dev works; enable it in production.
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(requestHandler)
            )
            // Ensure the XSRF-TOKEN cookie is written on every response, not only
            // when Spring Security itself needs the token. Without this, a GET request
            // that precedes the first POST/PATCH leaves the cookie unset and the next
            // state-changing request fails with 403.
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Welcome-listed public paths — no authentication required.
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/login.html",
                    "/register.html",
                    "/css/**",
                    "/js/**",
                    "/api/register"
                ).permitAll()
                // admin.js is only used by admin.html; restrict it so its contents
                // are not visible to unauthenticated scanners mapping the API surface.
                .requestMatchers("/js/admin.js").authenticated()
                // admin.html is restricted to ADMIN role at the HTTP layer.
                // @PreAuthorize on the service provides the primary enforcement;
                // this rule prevents non-admins from even receiving the HTML.
                .requestMatchers("/admin.html").hasRole("ADMIN")
                .requestMatchers("/dashboard.html").authenticated()
                // All other requests require authentication — deny by default.
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/dashboard.html", false)
                // Return 401 JSON instead of a redirect for API clients.
                .failureHandler((req, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Invalid credentials\"}");
                })
                .successHandler((req, res, auth) -> {
                    res.setStatus(HttpStatus.OK.value());
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\":\"Login successful\"}");
                })
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler((req, res, auth) -> {
                    res.setStatus(HttpStatus.OK.value());
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\":\"Logged out\"}");
                })
                .invalidateHttpSession(true)
                // Clear both the session cookie and the CSRF token cookie on logout.
                // Leaving XSRF-TOKEN in the browser after logout is harmless (no session
                // to attach it to), but cleaning it up avoids confusion on shared devices.
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .permitAll()
            )
            // Explicit session fixation protection. Spring Security defaults to
            // changeSessionId, but stating it here makes the intent clear and prevents
            // accidental removal if sessionManagement() is extended in the future.
            .sessionManagement(sm -> sm
                .sessionFixation().changeSessionId()
            )
            // Return 401 instead of redirecting to the login page for API calls.
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    request -> request.getRequestURI().startsWith("/api/")
                )
            );

        return http.build();
    }
}
