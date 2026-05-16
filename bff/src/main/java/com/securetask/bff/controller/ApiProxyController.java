package com.securetask.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Map;

@RestController
public class ApiProxyController {

    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final String serviceToken;

    public ApiProxyController(RestTemplate restTemplate,
                               @Value("${bff.api.base-url}") String apiBaseUrl,
                               @Value("${bff.service-token}") String serviceToken) {
        this.restTemplate = restTemplate;
        this.apiBaseUrl = apiBaseUrl;
        this.serviceToken = serviceToken;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(
            HttpMethod method,
            HttpServletRequest request,
            HttpSession session) throws IOException {

        byte[] body = request.getInputStream().readAllBytes();
        String accessToken = (String) session.getAttribute("accessToken");
        String targetUrl = buildTargetUrl(request);

        ResponseEntity<byte[]> apiResponse = callApi(method, targetUrl, body,
                request.getContentType(), accessToken);

        // Only attempt token refresh when a session token existed — unauthenticated
        // requests (e.g. /api/register) should get back the 401 from main API as-is.
        if (apiResponse.getStatusCode() == HttpStatus.UNAUTHORIZED && accessToken != null) {
            String newToken = tryRefresh(session);
            if (newToken != null) {
                apiResponse = callApi(method, targetUrl, body, request.getContentType(), newToken);
            } else {
                session.removeAttribute("accessToken");
                session.removeAttribute("refreshToken");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Session expired — please login again\"}".getBytes());
            }
        }

        return buildResponse(apiResponse);
    }

    private ResponseEntity<byte[]> callApi(HttpMethod method, String url, byte[] body,
                                            String contentType, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        // Always send the service token so the main API can exempt BFF calls from CSRF.
        headers.set("X-BFF-Service-Token", serviceToken);
        if (accessToken != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), byte[].class);
    }

    private String tryRefresh(HttpSession session) {
        String refreshToken = (String) session.getAttribute("refreshToken");
        if (refreshToken == null) return null;
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                apiBaseUrl + "/api/auth/refresh",
                Map.of("refreshToken", refreshToken),
                Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String newAccess = (String) response.getBody().get("accessToken");
                String newRefresh = (String) response.getBody().get("refreshToken");
                session.setAttribute("accessToken", newAccess);
                session.setAttribute("refreshToken", newRefresh);
                return newAccess;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String buildTargetUrl(HttpServletRequest request) {
        String query = request.getQueryString();
        return apiBaseUrl + request.getRequestURI() + (query != null ? "?" + query : "");
    }

    private ResponseEntity<byte[]> buildResponse(ResponseEntity<byte[]> apiResponse) {
        HttpHeaders responseHeaders = new HttpHeaders();
        MediaType contentType = apiResponse.getHeaders().getContentType();
        if (contentType != null) {
            responseHeaders.setContentType(contentType);
        }
        String disposition = apiResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (disposition != null) {
            responseHeaders.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        }
        return ResponseEntity.status(apiResponse.getStatusCode())
            .headers(responseHeaders)
            .body(apiResponse.getBody());
    }
}
