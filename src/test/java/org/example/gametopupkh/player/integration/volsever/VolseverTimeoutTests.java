package org.example.gametopupkh.player.integration.volsever;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.time.Duration;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.player.dto.PlayerVerifyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves a real read timeout fails closed instead of hanging the verification
 * request. Uses a loopback socket that accepts the connection but never replies.
 */
class VolseverTimeoutTests {

    @Test
    void readTimeoutFailsClosedAsUnavailable() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread slowServer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    Thread.sleep(2_000);
                } catch (Exception ignored) {
                    // The client is expected to abandon the request first.
                }
            }, "slow-volsever-test-server");
            slowServer.setDaemon(true);
            slowServer.start();

            VolseverProperties properties = new VolseverProperties();
            properties.setConnectTimeout(Duration.ofMillis(500));
            properties.setReadTimeout(Duration.ofMillis(200));
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build());
            factory.setReadTimeout(properties.getReadTimeout());
            RestClient http = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getLocalPort())
                    .defaultHeader("X-API-Key", "test-only-key")
                    .requestFactory(factory)
                    .build();
            VolseverPlayerLookupClient client = new VolseverPlayerLookupClient(http, properties);

            assertThatThrownBy(() -> client.lookup("free-fire",
                    new PlayerVerifyRequest("13318490370", null)))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(error.getCode()).isEqualTo("VOLSEVER_UNAVAILABLE");
                        assertThat(error.getMessage()).doesNotContain("test-only-key");
                        assertThat(error.getCause()).isNull();
                    });
        }
    }
}
