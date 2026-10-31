package org.example.gametopupkh.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing and startup validation of the browser origin allowlist. */
class CorsPropertiesTests {

    @Test
    void emptyConfigurationAllowsNoOrigin() {
        CorsProperties properties = new CorsProperties();

        assertThat(properties.origins()).isEmpty();
        assertThat(properties.isAllowedOriginsValid()).isTrue();
    }

    @Test
    void commaSeparatedOriginsAreSplitAndTrimmed() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins("https://gametopup-kh.vercel.app, https://shop.example.com");

        assertThat(properties.origins())
                .containsExactly("https://gametopup-kh.vercel.app", "https://shop.example.com");
        assertThat(properties.isAllowedOriginsValid()).isTrue();
    }

    @Test
    void wildcardIsRejected() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins("*");

        assertThat(properties.isAllowedOriginsValid()).isFalse();
    }

    @Test
    void originWithAPathIsRejected() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins("https://shop.example.com/checkout");

        assertThat(properties.isAllowedOriginsValid()).isFalse();
    }

    @Test
    void nonHttpSchemeIsRejected() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins("file://shop.example.com");

        assertThat(properties.isAllowedOriginsValid()).isFalse();
    }
}
