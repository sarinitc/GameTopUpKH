package org.example.gametopupkh.supplier.integration.bay2game;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Allowlisted read models; never return raw supplier payloads or authentication fields. */
public final class Bay2GameResponses {
    private Bay2GameResponses() { }

    public record Profile(long id, String username, BigDecimal balance, String accountStatus,
            String role, long totalOrders, BigDecimal totalSpent, Instant fetchedAt) { }

    /** Currency is null until the provider confirms the profile balance denomination. */
    public record Balance(BigDecimal amount, String currency, Instant fetchedAt) { }

    public record Categories(int total, List<Category> categories, Instant fetchedAt) { }

    public record Category(String gameCode, String name, String description, String imageUrl,
            List<String> gameFields) { }

    public record Products(Game game, List<Product> products, String currency, Instant fetchedAt) { }

    public record Game(String gameCode, String name, String description, String imageUrl) { }

    public record Product(long id, String productCode, String name, BigDecimal sellPrice, String status) { }
}
