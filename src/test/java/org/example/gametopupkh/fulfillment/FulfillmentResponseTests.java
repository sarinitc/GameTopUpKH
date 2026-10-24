package org.example.gametopupkh.fulfillment;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FulfillmentResponseTests {

    @Test
    void responseKeepsAnImmutableSnapshotOfDeliveryAndAttempts() {
        Fulfillment fulfillment = new Fulfillment();
        fulfillment.setStatus(FulfillmentStatus.PROCESSING);
        fulfillment.setAttemptCount(1);
        FulfillmentAttempt attempt = new FulfillmentAttempt();
        attempt.setAttemptNumber(1);
        attempt.setStatus(FulfillmentAttempt.Status.PENDING);
        List<FulfillmentAttempt> attempts = new ArrayList<>(List.of(attempt));

        FulfillmentResponse response = FulfillmentResponse.from(fulfillment, attempts);
        fulfillment.setStatus(FulfillmentStatus.SUCCEEDED);
        attempt.setStatus(FulfillmentAttempt.Status.SUCCESS);
        attempts.clear();

        assertThat(response.status()).isEqualTo(FulfillmentStatus.PROCESSING);
        assertThat(response.completedAt()).isNull();
        assertThat(response.attempts()).hasSize(1);
        assertThat(response.attempts().get(0).status()).isEqualTo(FulfillmentAttempt.Status.PENDING);
        assertThatThrownBy(() -> response.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
