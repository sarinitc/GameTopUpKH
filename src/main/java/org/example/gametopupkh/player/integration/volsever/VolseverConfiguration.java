package org.example.gametopupkh.player.integration.volsever;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VolseverProperties.class)
public class VolseverConfiguration {

    @Bean
    @Conditional(VolseverEnabledCondition.class)
    VolseverPlayerLookupClient volseverPlayerLookupClient(
            VolseverProperties properties,
            @Value("${VOLSEVER_API_KEY:}") String apiKey) {
        String key = requireApiKey(apiKey);
        // The key is a default header, never a query parameter, so it cannot leak
        // into request URLs, access logs or exception messages.
        RestClient http = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .defaultHeader("X-API-Key", key)
                .requestFactory(requestFactory(properties))
                .build();
        return new VolseverPlayerLookupClient(http, properties);
    }

    JdkClientHttpRequestFactory requestFactory(VolseverProperties properties) {
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.replaceAll("/+$", "");
    }

    private static String requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || !apiKey.equals(apiKey.strip())
                || apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException(
                    "Volsever is enabled but VOLSEVER_API_KEY is missing or invalid in the server environment");
        }
        return apiKey;
    }
}
