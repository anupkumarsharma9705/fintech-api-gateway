package com.fintech.fintech_gateway.config;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;

@Configuration
public class GatewayConfig {

    /**
     * Dedicated upstream client with explicit connect/read timeouts. Without
     * these, a slow backend can exhaust the gateway's Tomcat worker pool.
     * mTLS is opt-in and uses a Spring SSL bundle so key material never lives
     * in gateway-specific properties.
     */
    @Bean
    public RestClient backendRestClient(GatewayProperties properties, SslBundles sslBundles) {
        GatewayProperties.Backend backend = properties.getBackend();
        HttpClient.Builder httpClient = HttpClient.newBuilder()
                .connectTimeout(backend.getConnectTimeout());

        if (backend.getMtls().isEnabled()) {
            SslBundle bundle = sslBundles.getBundle(backend.getMtls().getBundle());
            try {
                httpClient.sslContext(bundle.createSslContext());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to initialize mTLS SSL bundle '" + backend.getMtls().getBundle() + "'", e);
            }
        }

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient.build());
        requestFactory.setReadTimeout(backend.getReadTimeout());

        return RestClient.builder()
                .baseUrl(backend.getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(GatewayProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                var origins = properties.getCors().getAllowedOrigins().stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .toList();
                if (origins.isEmpty()) {
                    return;
                }
                registry.addMapping("/**")
                        .allowedOrigins(origins.toArray(String[]::new))
                        .allowedMethods(properties.getCors().getAllowedMethods()
                                .toArray(String[]::new))
                        .allowedHeaders(properties.getCors().getAllowedHeaders()
                                .toArray(String[]::new))
                        .exposedHeaders(properties.getCors().getExposedHeaders()
                                .toArray(String[]::new))
                        .allowCredentials(false)
                        .maxAge(properties.getCors().getMaxAge().toSeconds());
            }
        };
    }
}
