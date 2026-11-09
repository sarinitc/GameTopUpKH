package org.example.gametopupkh.supplier.integration.vsc;

import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the VSC top-up adapter. Two independent gates apply before any outbound
 * call can happen: {@code supplier.vsc.enabled=true} (creates the client) and
 * {@code supplier.client=vsc} (selects it for fulfillment). Selection without
 * enablement fails fast in the registry; enablement without provider confirmation
 * fails validation at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VscProperties.class)
public class VscConfiguration {

    @Bean
    @ConditionalOnProperty(name = "supplier.vsc.enabled", havingValue = "true")
    VscSupplierClient vscSupplierClient(VscProperties properties) {
        // Direct process environment only: no YAML, .env import, CLI, DB or HTTP-bound credential.
        String apiKey = System.getenv("SUPPLIER_VSC_API_KEY");
        // Dedicated builder: application interceptors/observations must not capture
        // the merchantkey header or the player ID carried in the query string.
        RestClient http = RestClient.builder().requestFactory(requestFactory(properties)).build();
        return new VscSupplierClient(http, apiKey, properties.getMerchantName());
    }

    JdkClientHttpRequestFactory requestFactory(VscProperties properties) {
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }
}
