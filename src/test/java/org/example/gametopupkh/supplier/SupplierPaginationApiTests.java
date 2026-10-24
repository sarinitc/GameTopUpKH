package org.example.gametopupkh.supplier;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:supplier-pagination;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=supplier-pagination-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "fulfillment.worker.enabled=false", "payment.reconciliation.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class SupplierPaginationApiTests {
    private static final String SUPPLIERS = "/api/v1/admin/suppliers";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void defaultPageLimitsContentAndReportsTotals() throws Exception {
        for (int number = 1; number <= 21; number++) {
            insertSupplier(number, "ACTIVE", CREATED.plusSeconds(number));
        }

        admin(get(SUPPLIERS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.content", hasSize(20)))
                .andExpect(jsonPath("$.data.content[0].name").value("Supplier21"))
                .andExpect(jsonPath("$.data.content[19].name").value("Supplier2"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        admin(get(SUPPLIERS).param("page", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier1")))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        admin(get(SUPPLIERS).param("size", "100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(21)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void requestedPagesDoNotOverlapAndPastEndKeepsTotals() throws Exception {
        for (int number = 1; number <= 5; number++) {
            insertSupplier(number, "ACTIVE", CREATED.plusSeconds(number));
        }

        admin(get(SUPPLIERS).param("page", "0").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier5", "Supplier4")))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3));
        admin(get(SUPPLIERS).param("page", "1").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier3", "Supplier2")))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2));
        admin(get(SUPPLIERS).param("page", "2").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier1")))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3));
        admin(get(SUPPLIERS).param("page", "9").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)))
                .andExpect(jsonPath("$.data.page").value(9))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3));
    }

    @Test
    void statusFilterAppliesBeforePagingAndCountsOnlyMatchingSuppliers() throws Exception {
        insertSupplier(1, "ACTIVE", CREATED.plusSeconds(1));
        insertSupplier(2, "INACTIVE", CREATED.plusSeconds(2));
        insertSupplier(3, "ACTIVE", CREATED.plusSeconds(3));
        insertSupplier(4, "SUSPENDED", CREATED.plusSeconds(4));
        insertSupplier(5, "ACTIVE", CREATED.plusSeconds(5));

        admin(get(SUPPLIERS).param("status", "ACTIVE").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier5", "Supplier3")))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        admin(get(SUPPLIERS).param("status", "ACTIVE").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier1")))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void equalTimestampsUseDescendingIdAcrossPages() throws Exception {
        insertSupplier(1, "ACTIVE", CREATED);
        insertSupplier(3, "ACTIVE", CREATED);
        insertSupplier(2, "ACTIVE", CREATED);

        admin(get(SUPPLIERS).param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier3", "Supplier2")));
        admin(get(SUPPLIERS).param("size", "2").param("page", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].name", contains("Supplier1")));
    }

    @Test
    void emptyDatabaseAndNoMatchingStatusReturnEmptyPages() throws Exception {
        admin(get(SUPPLIERS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));

        insertSupplier(1, "INACTIVE", CREATED);
        admin(get(SUPPLIERS).param("status", "ACTIVE").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101", "2147483647,100", "1073741824,2"})
    void invalidBoundsAndOffsetOverflowReturnApiErrors(String page, String size) throws Exception {
        admin(get(SUPPLIERS).param("page", page).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    @ParameterizedTest
    @CsvSource({"page,abc", "page,1.5", "size,abc", "size,1.5", "page,2147483648", "size,2147483648"})
    void nonIntegerPaginationReturnsBadRequest(String parameter, String value) throws Exception {
        admin(get(SUPPLIERS).param(parameter, value)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("HTTP_400"));
    }

    private void insertSupplier(int number, String supplierStatus, Instant createdAt) {
        jdbc.update("insert into suppliers (id, name, status, created_at, updated_at) values (?, ?, ?, ?, ?)",
                new UUID(0, number), "Supplier" + number, supplierStatus,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user("admin").roles("ADMIN")));
    }
}
