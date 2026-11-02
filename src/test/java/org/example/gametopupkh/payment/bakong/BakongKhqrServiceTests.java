package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.time.Instant;

import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.bakong.dto.BakongKhqrResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BakongKhqrServiceTests {

    private BakongProperties properties;
    private BakongKhqrService service;

    @BeforeEach
    void setUp() {
        properties = new BakongProperties(
                "https://api-bakong.nbc.gov.kh",
                "test-api-token",
                "merchant@bank",
                "GameTopUp-KH"
        );
        service = new BakongKhqrService(properties);
    }

    @Test
    @DisplayName("Generates valid dynamic KHQR for USD with correct EMVCo structure and MD5")
    void generatesValidUsdDynamicKhqr() {
        BigDecimal amount = new BigDecimal("4.25");
        String currency = "USD";
        String reference = "PAY-REF-123456";

        BakongKhqrResponse response = service.generateDynamicKhqr(amount, currency, reference);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo("4.25");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.paymentReference()).isEqualTo(reference);
        assertThat(response.expiresAt()).isAfter(Instant.now());

        String qr = response.qr();
        // Tag 00: Version 01
        assertThat(qr).startsWith("000201");
        // Tag 01: Dynamic point of initiation (12)
        assertThat(qr).contains("010212");
        // Tag 29: Merchant account info
        assertThat(qr).contains("29");
        assertThat(qr).contains("merchant@bank");
        // Tag 52: MCC 5999
        assertThat(qr).contains("52045999");
        // Tag 53: USD code 840
        assertThat(qr).contains("5303840");
        // Tag 54: Amount 4.25
        assertThat(qr).contains("54044.25");
        // Tag 58: Country KH
        assertThat(qr).contains("5802KH");
        // Tag 59: Merchant Name
        assertThat(qr).contains("5912GameTopUp-KH");
        // Tag 60: City Phnom Penh
        assertThat(qr).contains("6010Phnom Penh");
        // Tag 62: Bill Number
        assertThat(qr).contains("PAY-REF-123456");
        // Tag 63: CRC16
        assertThat(qr).contains("6304");

        // Verify CRC16 checksum at the tail
        String payloadWithoutCrc = qr.substring(0, qr.length() - 4);
        String expectedCrc = BakongKhqrService.calculateCrc16(payloadWithoutCrc);
        assertThat(qr).endsWith(expectedCrc);

        // Verify MD5 hash
        assertThat(response.md5()).hasSize(32).matches("^[a-f0-9]{32}$");
        assertThat(response.md5()).isEqualTo(BakongKhqrService.calculateMd5(qr));
    }

    @Test
    @DisplayName("Generates valid dynamic KHQR for KHR currency")
    void generatesValidKhrDynamicKhqr() {
        BigDecimal amount = new BigDecimal("40000");
        String currency = "KHR";
        String reference = "PAY-KHR-001";

        BakongKhqrResponse response = service.generateDynamicKhqr(amount, currency, reference);

        assertThat(response.currency()).isEqualTo("KHR");
        assertThat(response.qr()).contains("5303116"); // KHR currency code
        assertThat(response.qr()).contains("540540000");
    }

    @Test
    @DisplayName("Rejects unsupported currencies")
    void rejectsUnsupportedCurrencies() {
        assertThatThrownBy(() -> service.generateDynamicKhqr(new BigDecimal("10.00"), "EUR", "REF-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Bakong KHQR only supports USD and KHR");

        assertThatThrownBy(() -> service.generateDynamicKhqr(new BigDecimal("10.00"), "THB", "REF-2"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Bakong KHQR only supports USD and KHR");
    }

    @Test
    @DisplayName("Rejects invalid zero or negative amounts")
    void rejectsInvalidAmounts() {
        assertThatThrownBy(() -> service.generateDynamicKhqr(BigDecimal.ZERO, "USD", "REF-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be greater than zero");

        assertThatThrownBy(() -> service.generateDynamicKhqr(new BigDecimal("-5.00"), "USD", "REF-2"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be greater than zero");
    }

    @Test
    @DisplayName("Expiration is capped at maximum 10 minutes")
    void expirationIsCappedAtTenMinutes() {
        BakongProperties longLifetimeProps = new BakongProperties(
                "production", false, "https://api-bakong.nbc.gov.kh", "token", "acc@bank",
                "GameTopUp-KH", "Phnom Penh", 30, java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(15), 2
        );
        BakongKhqrService customService = new BakongKhqrService(longLifetimeProps);
        BakongKhqrResponse response = customService.generateDynamicKhqr(new BigDecimal("5.00"), "USD", "REF-1");

        Instant maxAllowed = Instant.now().plusSeconds(600 + 5);
        assertThat(response.expiresAt()).isBeforeOrEqualTo(maxAllowed);
    }
}
