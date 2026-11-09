package org.example.gametopupkh.integration;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresSchemaTests extends PostgresIntegrationTest {

    @Test
    void allFlywayMigrationsApplyAndPostgresConstraintsAreEnforced() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
                .isEqualTo(15);
        assertThat(jdbc.queryForList("select name from roles order by name", String.class))
                .containsExactly("ADMIN", "SUPPORT", "USER");
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (full_name,email,password_hash,phone_number)
                values ('Invalid','invalid@example.test','unused','not-e164')
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void freeFirePackageSeedIsIdempotentAndKeepsExistingSkus() {
        jdbc.update("insert into games (name, slug) values ('Free Fire', 'free-fire')");
        ResourceDatabasePopulator seed = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V15__seed_free_fire_packages.sql"));

        seed.execute(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("select count(*) from game_packages", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "select count(*) from game_packages where is_active and currency = 'USD'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "select price from game_packages where sku = 'FF-1060'", BigDecimal.class))
                .isEqualByComparingTo("9.99");

        // A second run must not duplicate rows or fail on the unique SKU.
        seed.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("select count(*) from game_packages", Integer.class)).isEqualTo(4);
    }
}
