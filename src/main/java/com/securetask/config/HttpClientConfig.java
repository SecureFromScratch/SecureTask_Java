package com.securetask.config;

import com.securetask.service.OutboundHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;

@Configuration
public class HttpClientConfig {

    @Bean
    public OutboundHttpClient outboundHttpClient() {
        // Never follow redirects: a 3xx to an internal address would bypass SsrfGuard.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        RestClient client = RestClient.builder().requestFactory(factory).build();
        return (url, body) -> {
            try {
                return client.post()
                        .uri(url)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value();
            } catch (HttpStatusCodeException e) {
                return e.getStatusCode().value();
            }
        };
    }
}
