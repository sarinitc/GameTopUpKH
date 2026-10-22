package org.example.gametopupkh.order;

import java.util.List;
import java.util.UUID;

/**
 * Order business logic: creation with the catalog checks, the customer views and the
 * admin views.
 */
public interface OrderService {

    /**
     * Places an order for the given customer.
     *
     * @param userId  the authenticated customer
     * @param request the package, the player id and the optional contact details
     */
    OrderResponse create(UUID userId, OrderRequest request);

    /**
     * @return the orders of the given customer, newest first
     */
    List<OrderResponse> findByCustomer(UUID userId);

    /**
     * Reads a single order. A customer only sees their own orders, an admin sees all.
     *
     * @param orderId    the order to read
     * @param requesterId the authenticated caller
     * @param admin      whether the caller is an admin
     */
    OrderResponse findById(UUID orderId, UUID requesterId, boolean admin);

    /**
     * @param status optional status filter
     * @return every order, newest first
     */
    List<OrderResponse> findAll(OrderStatus status);
}
