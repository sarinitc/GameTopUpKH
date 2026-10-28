package org.example.gametopupkh.supplier.integration.bay2game;

import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Bay2GameProperties.class)
public class Bay2GameConfiguration {

    @Bean
    @ConditionalOnProperty(name = "supplier.bay2game.enabled", havingValue = "true")
    Bay2GameClient bay2GameClient(Bay2GameProperties properties) {
        // Direct process environment only: no YAML, .env import, DB, CLI or HTTP credential binding.
        String apiKey = System.getenv("BAY2GAME_API_KEY");
        // A dedicated builder avoids application interceptors/observations capturing query credentials.
        RestClient http = RestClient.builder().requestFactory(requestFactory(properties)).build();
        return new Bay2GameClient(http, apiKey);
    }

    JdkClientHttpRequestFactory requestFactory(Bay2GameProperties properties) {
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }
}
