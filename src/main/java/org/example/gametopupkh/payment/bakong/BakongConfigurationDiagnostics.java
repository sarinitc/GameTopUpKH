package org.example.gametopupkh.payment.bakong;

import java.net.URI;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Reports the resolved Bakong configuration at startup so an operator can verify
 * a deployment without reading secrets.
 *
 * <p>The token and account ID are never logged; only their presence is reported.
 * The base URL is reduced to its host because query strings and paths can carry
 * provider-specific values.</p>
 */
@Component
public class BakongConfigurationDiagnostics implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(BakongConfigurationDiagnostics.class);

    private static final Set<String> SANDBOX_HOSTS = Set.of(
            "sit-api-bakong.nbc.org.kh",
            "sit-bakong.nbc.org.kh",
            "api-test.nbc.gov.kh");

    private static final Set<String> PRODUCTION_HOSTS = Set.of(
            "api-bakong.nbc.gov.kh",
            "bakong.nbc.gov.kh",
            "api-bakong.nbc.org.kh",
            "bakong.nbc.org.kh");

    private final BakongProperties properties;

    public BakongConfigurationDiagnostics(BakongProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        String host = hostOf(properties.baseUrl());
        String environment = properties.isProduction() ? "production" : "sandbox";

        log.info("Bakong configuration: environment={}, enabled={}, baseUrlHost={}, tokenPresent={}, accountIdPresent={}",
                environment,
                properties.isEnabled(),
                host == null ? "[unparseable]" : host,
                StringUtils.hasText(properties.apiToken()),
                properties.hasAccountId());

        if (!properties.isProduction() && host != null && PRODUCTION_HOSTS.contains(host)) {
            log.warn("Bakong environment is sandbox but BAKONG_BASE_URL host {} is a production endpoint; "
                    + "keep BAKONG_ENVIRONMENT and BAKONG_BASE_URL in lockstep", host);
        }
        if (properties.isProduction() && host != null && SANDBOX_HOSTS.contains(host)) {
            log.warn("Bakong environment is production but BAKONG_BASE_URL host {} is a sandbox endpoint; "
                    + "keep BAKONG_ENVIRONMENT and BAKONG_BASE_URL in lockstep", host);
        }
    }

    private static String hostOf(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
