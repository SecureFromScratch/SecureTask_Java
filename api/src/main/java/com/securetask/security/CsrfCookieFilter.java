package com.securetask.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the deferred CSRF token to be loaded on every request so that
 * CookieCsrfTokenRepository writes the XSRF-TOKEN cookie in the response.
 *
 * Spring Security 6 uses lazy CSRF token loading by default. Without this
 * filter, the cookie is only written when the framework itself needs the token
 * (i.e. on a state-changing request). A GET that precedes the first POST/PATCH
 * leaves the cookie unset, causing the first state-changing request to fail
 * with 403 because the JavaScript client has no token to send.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        // Calling getToken() is enough — it triggers lazy loading and causes
        // CookieCsrfTokenRepository to write the cookie into the response.
        if (csrfToken != null) {
            csrfToken.getToken();
        }

        filterChain.doFilter(request, response);
    }
}
