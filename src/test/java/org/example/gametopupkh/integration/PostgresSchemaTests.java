package org.example.gametopupkh.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresSchemaTests extends PostgresIntegrationTest {

    @Test
    void allFlywayMigrationsApplyAndPostgresConstraintsAreEnforced() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
                .isEqualTo(11);
        assertThat(jdbc.queryForList("select name from roles order by name", String.class))
                .containsExactly("ADMIN", "SUPPORT", "USER");
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (full_name,email,password_hash,phone_number)
                values ('Invalid','invalid@example.test','unused','not-e164')
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
