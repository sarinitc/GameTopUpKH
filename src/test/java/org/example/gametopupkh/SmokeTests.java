package org.example.gametopupkh;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.example.gametopupkh.payment.gateway.MockPaymentGateway;
import org.example.gametopupkh.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeTests {

    @LocalServerPort
    private int port;

    @Autowired
    private List<PaymentGateway> gateways;

    @Test
    void testEndpointIsReachable() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/test"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("GameTopUp API is running!");
    }

    @Test
    void mockGatewayIsTheActiveImplementation() {
        assertThat(gateways).hasSize(1);
        assertThat(gateways.get(0)).isInstanceOf(MockPaymentGateway.class);
    }

    @Test
    void unknownPathRequiresAuthentication() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/does-not-exist"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"success\":false").contains("\"code\":\"UNAUTHORIZED\"");
    }
}
