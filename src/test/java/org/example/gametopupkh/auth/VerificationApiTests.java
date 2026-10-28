package org.example.gametopupkh.auth;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.example.gametopupkh.auth.verification.VerificationCodeSender;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VerificationApiTests {

    @MockitoBean
    private VerificationCodeSender verificationCodeSender;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestEmailVerificationCodeIsAccepted() throws Exception {
        String email = "verification-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Verification Test\",\"email\":\"" + email
                                + "\",\"password\":\"verification-test-password\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/verify-email/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
    }
}
