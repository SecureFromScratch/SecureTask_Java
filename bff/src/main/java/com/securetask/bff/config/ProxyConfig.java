package com.securetask.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ProxyConfig {

    @Bean
    public RestTemplate restTemplate() {
        // Use Apache HttpClient 5 to avoid HttpURLConnection's streaming-mode quirk:
        // SimpleClientHttpRequestFactory calls setFixedLengthStreamingMode() which causes
        // HttpURLConnection to throw HttpRetryException on any 401 response.
        RestTemplate rt = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
        // Forward all HTTP responses as-is (including 4xx/5xx) rather than throwing.
        // The proxy controller reads the status code and handles it explicitly.
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }
}
