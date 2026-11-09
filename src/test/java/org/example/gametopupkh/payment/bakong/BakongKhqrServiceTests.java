package org.example.gametopupkh.payment.bakong;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import kh.gov.nbc.bakong_khqr.BakongKHQR;
import kh.gov.nbc.bakong_khqr.model.CRCValidation;
import kh.gov.nbc.bakong_khqr.model.KHQRDecodeData;
import kh.gov.nbc.bakong_khqr.model.KHQRResponse;
import org.example.gametopupkh.common.exception.ApiException;
import org.example.gametopupkh.payment.bakong.dto.BakongKhqrResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BakongKhqrServiceTests {

    private BakongProperties properties;
    private BakongKhqrService service;

    @BeforeEach
    void setUp() {
        properties = sandboxProperties("merchant@bank", 10);
        service = new BakongKhqrService(properties);
    }

    @Test
    @DisplayName("Generates a genuine dynamic KHQR with the official NBC SDK")
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
        // Tag 99: SDK-provided creation/expiration timestamps
        assertThat(qr).contains("99");
        // Tag 63: CRC16
        assertThat(qr).contains("6304");

        KHQRResponse<CRCValidation> verification = BakongKHQR.verify(qr);
        assertThat(verification.getKHQRStatus().getCode()).isZero();
        assertThat(verification.getData().isValid()).isTrue();

        KHQRResponse<KHQRDecodeData> decoded = BakongKHQR.decode(qr);
        assertThat(decoded.getKHQRStatus().getCode()).isZero();
        assertThat(decoded.getData().getBakongAccountID()).isEqualTo("merchant@bank");
        assertThat(new BigDecimal(decoded.getData().getTransactionAmount())).isEqualByComparingTo("4.25");
        assertThat(decoded.getData().getTransactionCurrency()).isEqualTo("840");
        assertThat(decoded.getData().getBillNumber()).isEqualTo(reference);
        assertThat(decoded.getData().getExpirationTimestamp())
                .isEqualTo(String.valueOf(response.expiresAt().toEpochMilli()));

        // MD5 is computed over the final UTF-8 QR payload
        assertThat(response.md5()).hasSize(32).matches("^[a-f0-9]{32}$");
        assertThat(response.md5()).isEqualTo(BakongKhqrService.calculateMd5(qr));
    }

    @Test
    @DisplayName("Generates a genuine dynamic KHQR for KHR currency")
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
    @DisplayName("Rejects KHR amounts with riel decimals that KHQR cannot represent")
    void rejectsKhrDecimals() {
        assertThatThrownBy(() -> service.generateDynamicKhqr(new BigDecimal("40000.50"), "KHR", "REF-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("whole riel");
    }

    @Test
    @DisplayName("Rejects KHQR generation when no merchant account ID is configured")
    void rejectsMissingMerchantAccountId() {
        BakongKhqrService customService = new BakongKhqrService(sandboxProperties("", 10));

        assertThatThrownBy(() -> customService.generateDynamicKhqr(new BigDecimal("5.00"), "USD", "REF-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("account ID must be configured");
    }

    @Test
    @DisplayName("Production generation fails closed while live payments are disabled")
    void productionGenerationRequiresExplicitEnablement() {
        BakongProperties productionDisabled = new BakongProperties(
                "production", false, "https://api-bakong.nbc.gov.kh", "token", "merchant@bank",
                "GameTopUp-KH", "Phnom Penh", 10, Duration.ofSeconds(5),
                Duration.ofSeconds(15), 2);
        BakongKhqrService customService = new BakongKhqrService(productionDisabled);

        assertThatThrownBy(() -> customService.generateDynamicKhqr(new BigDecimal("5.00"), "USD", "REF-1"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("Production with live payments enabled but no token refuses to issue a QR")
    void productionEnabledWithoutTokenRefusesToGenerate() {
        BakongProperties productionWithoutToken = new BakongProperties(
                "production", true, "https://api-bakong.nbc.gov.kh", "", "merchant@bank",
                "GameTopUp-KH", "Phnom Penh", 10, Duration.ofSeconds(5),
                Duration.ofSeconds(15), 2);
        BakongKhqrService customService = new BakongKhqrService(productionWithoutToken);

        assertThatThrownBy(() -> customService.generateDynamicKhqr(new BigDecimal("5.00"), "USD", "REF-1"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessageContaining("BAKONG_TOKEN/BAKONG_API_TOKEN");
    }

    @Test
    @DisplayName("Sandbox preflight allows creation while the live switch is off")
    void sandboxPreflightAllowsCreationWhileLiveSwitchIsOff() {
        assertThatCode(service::ensurePaymentCreationAvailable).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production preflight rejects a missing merchant account before a payment is reserved")
    void productionPreflightRejectsMissingAccount() {
        BakongProperties productionWithoutAccount = new BakongProperties(
                "production", true, "https://api-bakong.nbc.gov.kh", "token", "",
                "GameTopUp-KH", "Phnom Penh", 10, Duration.ofSeconds(5),
                Duration.ofSeconds(15), 2);

        assertThatThrownBy(new BakongKhqrService(productionWithoutAccount)::ensurePaymentCreationAvailable)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessageContaining("account ID must be configured");
    }

    @Test
    @DisplayName("Expiration is capped at maximum 10 minutes")
    void expirationIsCappedAtTenMinutes() {
        BakongKhqrService customService = new BakongKhqrService(sandboxProperties("merchant@bank", 30));
        BakongKhqrResponse response = customService.generateDynamicKhqr(new BigDecimal("5.00"), "USD", "REF-1");

        Instant maxAllowed = Instant.now().plusSeconds(600 + 5);
        assertThat(response.expiresAt()).isBeforeOrEqualTo(maxAllowed);
    }

    private static BakongProperties sandboxProperties(String accountId, int lifetimeMinutes) {
        return new BakongProperties(
                "sandbox", false, "https://sit-api-bakong.nbc.org.kh", "test-api-token", accountId,
                "GameTopUp-KH", "Phnom Penh", lifetimeMinutes, Duration.ofSeconds(5),
                Duration.ofSeconds(15), 2);
    }
}
