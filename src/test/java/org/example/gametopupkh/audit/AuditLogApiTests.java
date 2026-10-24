package org.example.gametopupkh.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:audit-log-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=audit-log-api-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "fulfillment.worker.enabled=false", "payment.reconciliation.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class AuditLogApiTests {

    private static final String AUDIT_LOGS = "/api/v1/admin/audit-logs";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ACTOR_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ACTOR_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void adminCanListAndReadAuditLogsWhileOtherRolesAreDenied() throws Exception {
        UUID id = insertLog("CREATE", ACTOR_A, "admin@example.com", "games", "game-1", CREATED);
        insertLog("UPDATE", ACTOR_B, "other@example.com", "suppliers", "supplier-1", CREATED.plusSeconds(1));

        admin(get(AUDIT_LOGS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.data.content[1].id").value(id.toString()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        admin(get(AUDIT_LOGS + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.action").value("CREATE"))
                .andExpect(jsonPath("$.data.actorId").value(ACTOR_A.toString()))
                .andExpect(jsonPath("$.data.actorEmail").value("admin@example.com"))
                .andExpect(jsonPath("$.data.actorRole").value("ADMIN"))
                .andExpect(jsonPath("$.data.entityType").value("games"))
                .andExpect(jsonPath("$.data.entityId").value("game-1"))
                .andExpect(jsonPath("$.data.ipAddress").value("127.0.0.1"));

        mvc.perform(get(AUDIT_LOGS)).andExpect(status().isUnauthorized());
        mvc.perform(get(AUDIT_LOGS).with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(AUDIT_LOGS).with(user("support").roles("SUPPORT")))
                .andExpect(status().isForbidden());

        admin(get(AUDIT_LOGS + "/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIT_LOG_NOT_FOUND"));
    }

    @Test
    void filtersAndPaginationApplyToAuditLogs() throws Exception {
        insertLog("CREATE", ACTOR_A, "Alice@Example.com", "games", "game-1", CREATED.plusSeconds(1));
        insertLog("UPDATE", ACTOR_A, "alice@example.com", "games", "game-2", CREATED.plusSeconds(2));
        insertLog("STATUS_CHANGE", ACTOR_B, "bob@example.com", "suppliers", "supplier-1",
                CREATED.plusSeconds(3));
        insertLog("UPDATE", ACTOR_B, "bob@example.com", "suppliers", "supplier-2", CREATED.plusSeconds(4));
        insertLog("REFUND", ACTOR_A, "alice@example.com", "payments", "payment-1", CREATED.plusSeconds(5));

        admin(get(AUDIT_LOGS).param("action", "UPDATE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        admin(get(AUDIT_LOGS).param("actorId", ACTOR_B.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)));
        admin(get(AUDIT_LOGS).param("actor", ACTOR_A.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(3)));
        admin(get(AUDIT_LOGS).param("actor", "bob@example.com")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)));
        admin(get(AUDIT_LOGS).param("actorEmail", "ALICE@example.com")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(3)));
        admin(get(AUDIT_LOGS).param("entityType", "SUPPLIERS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(2)));
        admin(get(AUDIT_LOGS).param("entityType", "games").param("entityId", "game-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].action").value("UPDATE"));
        admin(get(AUDIT_LOGS).param("from", CREATED.plusSeconds(3).toString())
                .param("to", CREATED.plusSeconds(4).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].action", contains("UPDATE", "STATUS_CHANGE")));

        admin(get(AUDIT_LOGS).param("page", "0").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].action", contains("REFUND", "UPDATE")))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3));
        admin(get(AUDIT_LOGS).param("page", "2").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].action", contains("CREATE")))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(2));
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101", "2147483647,100"})
    void invalidPaginationReturnsApiError(String page, String size) throws Exception {
        admin(get(AUDIT_LOGS).param("page", page).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    @Test
    void invalidDateRangeReturnsApiError() throws Exception {
        admin(get(AUDIT_LOGS)
                .param("from", CREATED.plusSeconds(10).toString())
                .param("to", CREATED.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void successfulAdminMutationsAreRecordedAutomatically() throws Exception {
        admin(post("/api/v1/admin/suppliers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Audited Supplier\"}"))
                .andExpect(status().isCreated());

        admin(get(AUDIT_LOGS).param("action", "CREATE").param("entityType", "suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data.content[0].entityType").value("suppliers"))
                .andExpect(jsonPath("$.data.content[0].description", startsWith("POST /api/v1/admin/suppliers")))
                .andExpect(jsonPath("$.data.content[0].ipAddress").value("127.0.0.1"));

        String supplierId = jdbc.queryForObject(
                "select id from suppliers where name = 'Audited Supplier'", UUID.class).toString();
        admin(patch("/api/v1/admin/suppliers/" + supplierId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        admin(get(AUDIT_LOGS).param("action", "STATUS_CHANGE").param("entityType", "suppliers")
                .param("entityId", supplierId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].entityId").value(supplierId));
    }

    private UUID insertLog(String action, UUID actorId, String actorEmail, String entityType,
            String entityId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into audit_logs (id, action, actor_id, actor_email, actor_role, entity_type,
                    entity_id, description, details, ip_address, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, action, actorId, actorEmail, "ADMIN", entityType, entityId,
                "Test audit entry", "{\"source\":\"test\"}", "127.0.0.1", Timestamp.from(createdAt));
        return id;
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user("admin").roles("ADMIN")));
    }
}
