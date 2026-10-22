package org.example.gametopupkh.fulfillment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentAttemptRepository extends JpaRepository<FulfillmentAttempt, UUID> {

    List<FulfillmentAttempt> findAllByFulfillmentIdOrderByAttemptNumberAsc(UUID fulfillmentId);
}
