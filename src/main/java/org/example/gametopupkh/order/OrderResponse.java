package org.example.gametopupkh.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as returned to customers and admins.
 *
 * @param gameName    name of the game at the time the order was placed
 * @param packageName name of the package at the time the order was placed
 * @param history     status timeline of the order, oldest entry first
 */
public record OrderResponse(
        UUID id,
        String orderNumber,
        UUID packageId,
        String playerId,
        String zoneId,
        String contactEmail,
        String contactPhone,
        String gameName,
        String packageName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal totalAmount,
        String currency,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<StatusEntry> history) {

    public static OrderResponse from(Order order, List<OrderStatusHistory> history) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getPackageId(),
                order.getPlayerId(),
                order.getZoneId(),
                order.getContactEmail(),
                order.getContactPhone(),
                order.getGameNameSnapshot(),
                order.getPackageNameSnapshot(),
                order.getUnitPrice(),
                order.getQuantity(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                history.stream().map(StatusEntry::from).toList());
    }

    /**
     * One entry of the status timeline of an order.
     */
    public record StatusEntry(
            OrderStatus status,
            String note,
            Instant changedAt) {

        public static StatusEntry from(OrderStatusHistory history) {
            return new StatusEntry(history.getStatus(), history.getNote(), history.getChangedAt());
        }
    }
}
