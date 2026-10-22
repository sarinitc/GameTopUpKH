package org.example.gametopupkh.order;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.example.gametopupkh.catalog.game.entity.Game;
import org.example.gametopupkh.catalog.game.repository.GameRepository;
import org.example.gametopupkh.catalog.packagecatalog.entity.GamePackage;
import org.example.gametopupkh.catalog.packagecatalog.repository.GamePackageRepository;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements OrderService {

    private static final String ORDER_NUMBER_PREFIX = "ORD";
    private static final DateTimeFormatter ORDER_NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int ORDER_NUMBER_ATTEMPTS = 5;
    private static final int ORDER_NUMBER_RANDOM_BYTES = 4;

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final GamePackageRepository gamePackageRepository;
    private final GameRepository gameRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public OrderServiceImpl(OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            GamePackageRepository gamePackageRepository,
            GameRepository gameRepository,
            UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.gamePackageRepository = gamePackageRepository;
        this.gameRepository = gameRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public OrderResponse create(UUID userId, OrderRequest request) {
        User customer = requireActiveUser(userId);

        GamePackage gamePackage = requireActivePackage(request.packageId());
        Game game = requireActiveGame(gamePackage.getGameId());

        String playerId = request.playerId() == null ? null : request.playerId().trim();
        if (playerId == null || playerId.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYER_ID_REQUIRED",
                    "The player id is required");
        }

        String zoneId = normalize(request.zoneId());
        if (game.isRequiresZoneId() && zoneId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ZONE_ID_REQUIRED",
                    "The game " + game.getName() + " requires a zone id");
        }
        if (!game.isRequiresZoneId()) {
            zoneId = null;
        }

        int quantity = 1;
        BigDecimal unitPrice = gamePackage.getPrice();

        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setUser(customer);
        order.setPackageId(gamePackage.getId());
        order.setPlayerId(playerId);
        order.setZoneId(zoneId);
        order.setContactEmail(normalizeEmail(request.contactEmail()));
        order.setContactPhone(normalize(request.contactPhone()));
        order.setGameNameSnapshot(game.getName());
        order.setPackageNameSnapshot(gamePackage.getName());
        order.setUnitPrice(unitPrice);
        order.setQuantity(quantity);
        order.setTotalAmount(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        order.setCurrency(gamePackage.getCurrency());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        Order saved = orderRepository.save(order);

        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrderId(saved.getId());
        history.setStatus(OrderStatus.PENDING_PAYMENT);
        history.setNote("Order created");
        orderStatusHistoryRepository.save(history);

        return OrderResponse.from(saved, List.of(history));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> findByCustomer(UUID userId) {
        return toResponses(orderRepository.findAllByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse findById(UUID orderId, UUID requesterId, boolean admin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> notFound(orderId));
        if (!admin && (order.getUser() == null || !order.getUser().getId().equals(requesterId))) {
            throw notFound(orderId);
        }
        List<OrderStatusHistory> history =
                orderStatusHistoryRepository.findAllByOrderIdOrderByChangedAtAsc(orderId);
        return OrderResponse.from(order, history);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> findAll(OrderStatus status) {
        List<Order> orders = status == null
                ? orderRepository.findAllByOrderByCreatedAtDesc()
                : orderRepository.findAllByStatusOrderByCreatedAtDesc(status);
        return toResponses(orders);
    }

    private User requireActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Authentication is required to access this resource"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE",
                    "The account is " + user.getAccountStatus().name().toLowerCase(Locale.ROOT));
        }
        return user;
    }

    private GamePackage requireActivePackage(UUID packageId) {
        GamePackage gamePackage = gamePackageRepository.findById(packageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                        "Package " + packageId + " was not found"));
        if (!gamePackage.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PACKAGE_INACTIVE",
                    "The package " + gamePackage.getName() + " is no longer available");
        }
        return gamePackage;
    }

    private Game requireActiveGame(UUID gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GAME_NOT_FOUND",
                        "Game " + gameId + " was not found"));
        if (!game.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GAME_INACTIVE",
                    "The game " + game.getName() + " is no longer available");
        }
        return game;
    }

    private List<OrderResponse> toResponses(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = orders.stream().map(Order::getId).toList();
        Map<UUID, List<OrderStatusHistory>> historyByOrder = orderStatusHistoryRepository
                .findAllByOrderIdInOrderByChangedAtAsc(orderIds).stream()
                .collect(Collectors.groupingBy(OrderStatusHistory::getOrderId));
        return orders.stream()
                .map(order -> OrderResponse.from(order, historyByOrder.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    private String generateOrderNumber() {
        for (int attempt = 0; attempt < ORDER_NUMBER_ATTEMPTS; attempt++) {
            String candidate = ORDER_NUMBER_PREFIX + "-"
                    + LocalDate.now(ZoneOffset.UTC).format(ORDER_NUMBER_DATE) + "-"
                    + randomSuffix();
            if (!orderRepository.existsByOrderNumber(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ORDER_NUMBER_UNAVAILABLE",
                "Could not allocate an order number, please retry");
    }

    private String randomSuffix() {
        byte[] bytes = new byte[ORDER_NUMBER_RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private ApiException notFound(UUID orderId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                "Order " + orderId + " was not found");
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
