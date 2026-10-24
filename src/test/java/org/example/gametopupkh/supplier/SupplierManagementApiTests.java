package org.example.gametopupkh.supplier;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:supplier-management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.jwt.secret=supplier-management-test-secret-at-least-thirty-two-bytes",
        "payment.gateway=mock", "supplier.client=mock", "supplier.mock.fail=false",
        "fulfillment.worker.enabled=false", "payment.reconciliation.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class SupplierManagementApiTests {
    private static final String SUPPLIERS = "/api/v1/admin/suppliers";
    private static final String PRODUCTS = "/api/v1/admin/supplier-products";
    @Autowired private MockMvc mvc;

    @Test
    void supplierLifecycleDefaultsFiltersRenameAndStatus() throws Exception {
        String inactive = id(admin(post(SUPPLIERS), "{\"name\":\"  Inactive supplier  \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Inactive supplier"))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.createdAt").exists()).andExpect(jsonPath("$.data.updatedAt").exists()));
        String active = supplier("ACTIVE");
        admin(get(SUPPLIERS)).andExpect(status().isOk()).andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].id").value(active));
        admin(get(SUPPLIERS).param("status", "INACTIVE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1))).andExpect(jsonPath("$.data.content[0].id").value(inactive));
        admin(put(SUPPLIERS + "/" + inactive), "{\"name\":\" Renamed \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Renamed"))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        admin(patch(SUPPLIERS + "/" + inactive + "/status"), "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        admin(get(SUPPLIERS + "/" + inactive)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        admin(put(SUPPLIERS + "/" + inactive), "{\"name\":\"Other\",\"status\":\"ACTIVE\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void supplierNameLimitAppliesAfterTrimmingOnCreateAndUpdate() throws Exception {
        String name = "n".repeat(150);
        String supplier = id(admin(post(SUPPLIERS), "{\"name\":\"  " + name + "  \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value(name)));
        admin(put(SUPPLIERS + "/" + supplier), "{\"name\":\" " + name + " \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value(name));
    }

    @Test
    void externalSkuLimitAppliesAfterTrimmingOnCreateAndUpdate() throws Exception {
        String sku = "s".repeat(150);
        String supplier = supplier("ACTIVE");
        String pkg = gamePackage();
        String mapping = id(admin(post(PRODUCTS), mappingBody(supplier, pkg, "  " + sku + "  ", "1"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.externalSku").value(sku)));
        admin(put(PRODUCTS + "/" + mapping), "{\"externalSku\":\" " + sku + " \",\"costPrice\":2}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.externalSku").value(sku));
    }

    @Test
    void supplierInvalidFieldsAndMissingResources() throws Exception {
        for (String body : List.of("{}", "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(151) + "\"}",
                "{\"name\":\"Test\",\"status\":\"UNKNOWN\"}")) {
            admin(post(SUPPLIERS), body).andExpect(status().isBadRequest());
        }
        String existing = supplier("ACTIVE");
        admin(put(SUPPLIERS + "/" + existing), "{\"name\":\"" + "x".repeat(151) + "\"}")
                .andExpect(status().isBadRequest());
        admin(patch(SUPPLIERS + "/" + existing + "/status"), "{}")
                .andExpect(status().isBadRequest());
        String missing = SUPPLIERS + "/" + UUID.randomUUID();
        admin(get(missing)).andExpect(status().isNotFound());
        admin(put(missing), "{\"name\":\"Unknown\"}").andExpect(status().isNotFound());
        admin(patch(missing + "/status"), "{\"status\":\"ACTIVE\"}").andExpect(status().isNotFound());
        admin(get(SUPPLIERS).param("status", "UNKNOWN")).andExpect(status().isBadRequest());
    }

    @Test
    void mappingLifecycleTrimsCaseAndRetainsCurrencyAndParents() throws Exception {
        String supplier = supplier("ACTIVE");
        String pkg = gamePackage();
        String mapping = id(admin(post(PRODUCTS), mappingBody(supplier, pkg, "  Mixed-Case  ", "0"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.externalSku").value("Mixed-Case"))
                .andExpect(jsonPath("$.data.currency").value("USD")).andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.costPrice").value(0)));
        admin(put(PRODUCTS + "/" + mapping), "{\"externalSku\":\" New-Sku \",\"costPrice\":12.34,\"currency\":\"KHR\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.externalSku").value("New-Sku"))
                .andExpect(jsonPath("$.data.currency").value("KHR"));
        admin(put(PRODUCTS + "/" + mapping), "{\"externalSku\":\"Final\",\"costPrice\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currency").value("KHR"))
                .andExpect(jsonPath("$.data.supplierId").value(supplier)).andExpect(jsonPath("$.data.packageId").value(pkg));
        admin(patch(PRODUCTS + "/" + mapping + "/status"), "{\"active\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        admin(get(PRODUCTS + "/" + mapping)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        for (String extra : List.of("\"supplierId\":\"" + UUID.randomUUID() + "\"", "\"packageId\":\"" + UUID.randomUUID() + "\"", "\"active\":true")) {
            admin(put(PRODUCTS + "/" + mapping), "{\"externalSku\":\"NoChange\",\"costPrice\":2," + extra + "}")
                    .andExpect(status().isBadRequest());
        }
        admin(get(PRODUCTS + "/" + mapping)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalSku").value("Final")).andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void mappingsFilterTogetherAndAllowMultipleSkusForPackage() throws Exception {
        String a = supplier("ACTIVE"); String b = supplier("ACTIVE");
        String p = gamePackage(); String q = gamePackage();
        String first = mapping(a, p, "One");
        String second = mapping(a, p, "Two");
        mapping(a, q, "Three"); mapping(b, p, "One");
        admin(patch(PRODUCTS + "/" + second + "/status"), "{\"active\":false}").andExpect(status().isOk());
        admin(get(PRODUCTS)).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(4)));
        admin(get(PRODUCTS).param("supplierId", a).param("packageId", p)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
        admin(get(PRODUCTS).param("supplierId", a).param("packageId", p).param("active", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(first));
        admin(get(PRODUCTS).param("active", "false")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].id").value(second));
        admin(get(PRODUCTS).param("supplierId", UUID.randomUUID().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void duplicateSkusCreateAndUpdateReturnConflictButCaseAndSupplierAreDistinct() throws Exception {
        String supplier = supplier("ACTIVE"); String pkg = gamePackage();
        String first = mapping(supplier, pkg, "MixedCase"); String second = mapping(supplier, pkg, "Other");
        admin(post(PRODUCTS), mappingBody(supplier, pkg, " MixedCase ", "1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_SKU_EXISTS"));
        admin(put(PRODUCTS + "/" + second), "{\"externalSku\":\" MixedCase \",\"costPrice\":2}")
                .andExpect(status().isConflict());
        admin(put(PRODUCTS + "/" + first), "{\"externalSku\":\"MixedCase\",\"costPrice\":2}")
                .andExpect(status().isOk());
        mapping(supplier, pkg, "mixedcase"); mapping(supplier("ACTIVE"), pkg, "MixedCase");
    }

    @Test
    void inactiveParentsPreventCreateAndEnableButKeepAdminAccessAndDisableAvailable() throws Exception {
        String supplier = supplier("ACTIVE"); String pkg = gamePackage();
        String mapping = mapping(supplier, pkg, "Existing");
        admin(patch(SUPPLIERS + "/" + supplier + "/status"), "{\"status\":\"SUSPENDED\"}").andExpect(status().isOk());
        admin(get(PRODUCTS + "/" + mapping)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
        admin(post(PRODUCTS), mappingBody(supplier, pkg, "New", "1")).andExpect(status().isConflict());
        admin(post(PRODUCTS), mappingBody(supplier, pkg, "New", "1").replace("}", ",\"active\":false}"))
                .andExpect(status().isConflict());
        admin(patch(PRODUCTS + "/" + mapping + "/status"), "{\"active\":false}").andExpect(status().isOk());
        admin(put(PRODUCTS + "/" + mapping), "{\"externalSku\":\"Edited\",\"costPrice\":2}").andExpect(status().isOk());
        admin(patch(PRODUCTS + "/" + mapping + "/status"), "{\"active\":true}").andExpect(status().isConflict());
        admin(patch(SUPPLIERS + "/" + supplier + "/status"), "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        admin(patch("/api/v1/admin/packages/" + pkg + "/status"), "{\"active\":false}").andExpect(status().isOk());
        admin(post(PRODUCTS), mappingBody(supplier, pkg, "New", "1")).andExpect(status().isConflict());
        admin(patch(PRODUCTS + "/" + mapping + "/status"), "{\"active\":true}").andExpect(status().isConflict());
        admin(patch(PRODUCTS + "/" + mapping + "/status"), "{\"active\":false}").andExpect(status().isOk());
        admin(patch("/api/v1/admin/packages/" + pkg + "/status"), "{\"active\":true}").andExpect(status().isOk());
        admin(patch(PRODUCTS + "/" + mapping + "/status"), "{\"active\":true}").andExpect(status().isOk());
        admin(post(PRODUCTS), mappingBody(supplier("INACTIVE"), pkg, "Inactive", "1")).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "long", "negative", "large", "precision", "currency", "missingCost", "missingSku"})
    void invalidMappingFieldsAreRejectedOnCreateAndUpdate(String scenario) throws Exception {
        String supplier = supplier("ACTIVE"); String pkg = gamePackage(); String mapping = mapping(supplier, pkg, "Existing");
        String sku = switch (scenario) { case "long" -> "x".repeat(151); case "", "   " -> scenario; default -> "Valid"; };
        String cost = switch (scenario) { case "negative" -> "-0.01"; case "large" -> "10000000000"; case "precision" -> "1.001"; default -> "1"; };
        String body = mappingBody(supplier, pkg, sku, cost);
        if (scenario.equals("currency")) body = body.replace("}", ",\"currency\":\"usd\"}");
        if (scenario.equals("missingCost")) body = body.replace(",\"costPrice\":1", "");
        if (scenario.equals("missingSku")) body = body.replace(",\"externalSku\":\"Valid\"", "");
        admin(post(PRODUCTS), body).andExpect(status().isBadRequest());
        admin(put(PRODUCTS + "/" + mapping), body).andExpect(status().isBadRequest());
    }

    @Test
    void requiredParentsStatusMalformedParametersAndMissingIds() throws Exception {
        String supplier = supplier("ACTIVE"); String pkg = gamePackage();
        admin(post(PRODUCTS), "{\"externalSku\":\"Sku\",\"costPrice\":1}").andExpect(status().isBadRequest());
        admin(post(PRODUCTS), mappingBody(UUID.randomUUID().toString(), pkg, "Sku", "1")).andExpect(status().isNotFound());
        admin(post(PRODUCTS), mappingBody(supplier, UUID.randomUUID().toString(), "Sku", "1")).andExpect(status().isNotFound());
        String existing = mapping(supplier, pkg, "Existing");
        admin(patch(PRODUCTS + "/" + existing + "/status"), "{}").andExpect(status().isBadRequest());
        String missing = PRODUCTS + "/" + UUID.randomUUID();
        admin(get(missing)).andExpect(status().isNotFound());
        admin(put(missing), "{\"externalSku\":\"Sku\",\"costPrice\":1}").andExpect(status().isNotFound());
        admin(patch(missing + "/status"), "{\"active\":true}").andExpect(status().isNotFound());
        admin(get(PRODUCTS + "/invalid-uuid")).andExpect(status().isBadRequest());
        admin(get(PRODUCTS).param("active", "invalid")).andExpect(status().isBadRequest());
        admin(post(PRODUCTS), "{").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SUPPORT", "RESELLER", "ANONYMOUS"})
    void everyManagementRouteRequiresAdmin(String role) throws Exception {
        String id = UUID.randomUUID().toString();
        List<MockHttpServletRequestBuilder> routes = List.of(get(SUPPLIERS).param("page", "0").param("size", "2"), post(SUPPLIERS), get(SUPPLIERS + "/" + id),
                put(SUPPLIERS + "/" + id), patch(SUPPLIERS + "/" + id + "/status"), get(PRODUCTS), post(PRODUCTS),
                get(PRODUCTS + "/" + id), put(PRODUCTS + "/" + id), patch(PRODUCTS + "/" + id + "/status"));
        for (MockHttpServletRequestBuilder request : routes) {
            if (!role.equals("ANONYMOUS")) request.with(user("restricted").roles(role));
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(role.equals("ANONYMOUS") ? 401 : 403));
        }
    }

    private String supplier(String status) throws Exception {
        return id(admin(post(SUPPLIERS), "{\"name\":\"Supplier\",\"status\":\"" + status + "\"}").andExpect(status().isCreated()));
    }
    private String gamePackage() throws Exception {
        String game = id(admin(post("/api/v1/admin/games"), "{\"name\":\"Test\",\"slug\":\"supplier-" + UUID.randomUUID()
                + "\",\"playerIdLabel\":\"ID\",\"requiresZoneId\":false}").andExpect(status().isCreated()));
        return id(admin(post("/api/v1/admin/packages"), "{\"gameId\":\"" + game + "\",\"name\":\"Package\",\"sku\":\""
                + UUID.randomUUID() + "\",\"diamondAmount\":60,\"price\":10}").andExpect(status().isCreated()));
    }
    private String mapping(String supplier, String pkg, String sku) throws Exception {
        return id(admin(post(PRODUCTS), mappingBody(supplier, pkg, sku, "1")).andExpect(status().isCreated()));
    }
    private String mappingBody(String supplier, String pkg, String sku, String cost) {
        return "{\"supplierId\":\"" + supplier + "\",\"packageId\":\"" + pkg + "\",\"externalSku\":\"" + sku + "\",\"costPrice\":" + cost + "}";
    }
    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request.with(user("admin").roles("ADMIN"))); }
    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        return admin(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String id(ResultActions actions) throws Exception { return JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.data.id"); }
}
