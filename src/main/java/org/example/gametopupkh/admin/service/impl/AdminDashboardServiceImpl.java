package org.example.gametopupkh.admin.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.example.gametopupkh.admin.dto.AdminDashboardSummaryResponse;
import org.example.gametopupkh.admin.service.AdminDashboardService;
import org.example.gametopupkh.fulfillment.Fulfillment;
import org.example.gametopupkh.fulfillment.FulfillmentRepository;
import org.example.gametopupkh.fulfillment.FulfillmentStatus;
import org.example.gametopupkh.order.Order;
import org.example.gametopupkh.order.OrderRepository;
import org.example.gametopupkh.order.OrderStatus;
import org.example.gametopupkh.payment.Payment;
import org.example.gametopupkh.payment.PaymentRepository;
import org.example.gametopupkh.payment.PaymentStatus;
import org.example.gametopupkh.refund.PaymentRefund;
import org.example.gametopupkh.refund.RefundRepository;
import org.example.gametopupkh.refund.RefundStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final FulfillmentRepository fulfillmentRepository;

    public AdminDashboardServiceImpl(OrderRepository orderRepository, PaymentRepository paymentRepository,
            RefundRepository refundRepository, FulfillmentRepository fulfillmentRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.fulfillmentRepository = fulfillmentRepository;
    }

    @Override
    public AdminDashboardSummaryResponse getSummary() {
        List<Order> orders = orderRepository.findAll();
        List<Payment> payments = paymentRepository.findAll();
        List<PaymentRefund> refunds = refundRepository.findAll();
        List<Fulfillment> fulfillments = fulfillmentRepository.findAll();

        long totalOrders = orders.size();
        long pendingPaymentOrders = count(orders, o -> o.getStatus() == OrderStatus.PENDING_PAYMENT);
        long paidOrders = count(orders, o -> o.getStatus() == OrderStatus.PAID);
        long processingOrders = count(orders, o -> o.getStatus() == OrderStatus.PROCESSING);
        long completedOrders = count(orders, o -> o.getStatus() == OrderStatus.COMPLETED);
        long failedOrders = count(orders, o -> o.getStatus() == OrderStatus.FAILED);
        long cancelledOrders = count(orders, o -> o.getStatus() == OrderStatus.CANCELLED);
        long needsReviewOrders = count(orders, o -> o.getStatus() == OrderStatus.NEEDS_REVIEW);
        long refundedOrders = count(orders, o -> o.getStatus() == OrderStatus.REFUNDED);

        BigDecimal totalRevenue = orders.stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED && o.getStatus() != OrderStatus.PENDING_PAYMENT)
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal verifiedRevenue = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal successfulRefunds = refunds.stream()
                .filter(r -> r.getStatus() == RefundStatus.SUCCEEDED)
                .map(PaymentRefund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pendingTransactionAmount = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.INITIALIZING
                        || p.getStatus() == PaymentStatus.NEEDS_REVIEW)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalPayments = payments.size();
        long completedPayments = count(payments, p -> p.getStatus() == PaymentStatus.COMPLETED);
        long pendingPayments = count(payments,
                p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.INITIALIZING);
        long failedPayments = count(payments, p -> p.getStatus() == PaymentStatus.FAILED);
        long needsReviewPayments = count(payments, p -> p.getStatus() == PaymentStatus.NEEDS_REVIEW);

        long totalRefunds = refunds.size();
        long pendingRefunds = count(refunds,
                r -> r.getStatus() == RefundStatus.PENDING || r.getStatus() == RefundStatus.PROCESSING);
        long succeededRefunds = count(refunds, r -> r.getStatus() == RefundStatus.SUCCEEDED);
        long failedRefunds = count(refunds, r -> r.getStatus() == RefundStatus.FAILED || r.getStatus() == RefundStatus.CANCELLED);

        long totalFulfillments = fulfillments.size();
        long pendingFulfillments = count(fulfillments, f -> f.getStatus() == FulfillmentStatus.PENDING);
        long processingFulfillments = count(fulfillments, f -> f.getStatus() == FulfillmentStatus.PROCESSING);
        long succeededFulfillments = count(fulfillments, f -> f.getStatus() == FulfillmentStatus.SUCCEEDED);
        long failedFulfillments = count(fulfillments, f -> f.getStatus() == FulfillmentStatus.FAILED);

        return new AdminDashboardSummaryResponse(totalOrders, pendingPaymentOrders, paidOrders, processingOrders,
                completedOrders, failedOrders, cancelledOrders, needsReviewOrders, refundedOrders, totalRevenue,
                verifiedRevenue, successfulRefunds, pendingTransactionAmount, totalPayments, completedPayments,
                pendingPayments, failedPayments, needsReviewPayments, totalRefunds, pendingRefunds, succeededRefunds,
                failedRefunds, totalFulfillments, pendingFulfillments, processingFulfillments, succeededFulfillments,
                failedFulfillments);
    }

    private <T> long count(List<T> list, java.util.function.Predicate<T> predicate) {
        long count = 0;
        for (T item : list) {
            if (predicate.test(item)) {
                count++;
            }
        }
        return count;
    }
}
