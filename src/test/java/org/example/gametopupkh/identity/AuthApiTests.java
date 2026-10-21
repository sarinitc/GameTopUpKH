package org.example.gametopupkh.identity;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.example.gametopupkh.identity.entity.AccountStatus;
import org.example.gametopupkh.identity.entity.Role;
import org.example.gametopupkh.identity.entity.User;
import org.example.gametopupkh.identity.repository.RoleRepository;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthApiTests {

    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registerReturnsTokensAndProfile() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sokha\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").isNumber())
                .andExpect(jsonPath("$.data.user.fullName").value("Sokha"))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.roles[0]").value("USER"));
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sokha\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registerRejectsMissingContactChannel() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sokha\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTACT_REQUIRED"));
    }

    @Test
    void registerRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"\",\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginWithEmailReturnsTokens() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value(email));
    }

    @Test
    void loginWithPhoneNumberReturnsTokens() throws Exception {
        String phone = uniquePhoneNumber();
        registerWithPhone(phone);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + phone + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.phoneNumber").value(phone));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void accessTokenOpensProtectedResources() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(get("/api/v1/protected-resource").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void protectedResourceRejectsAnonymousCaller() throws Exception {
        mockMvc.perform(get("/api/v1/protected-resource"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void brokenTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/protected-resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void adminEndpointRejectsRegularUser() throws Exception {
        String token = register(uniqueEmail());

        mockMvc.perform(post("/api/v1/admin/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mobile Legends\",\"slug\":\"ml-" + uniqueSlug() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminEndpointAcceptsAdminUser() throws Exception {
        String email = uniqueEmail();
        createUser(email, "ADMIN");
        String token = login(email);

        mockMvc.perform(post("/api/v1/admin/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mobile Legends\",\"slug\":\"ml-" + uniqueSlug() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Mobile Legends"));
    }

    @Test
    void refreshReturnsNewTokensAndRevokesTheOldRefreshToken() throws Exception {
        String email = uniqueEmail();
        String refreshToken = registerRefreshToken(email);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String newRefreshToken = JsonPath.read(result.getResponse().getContentAsString(), "$.data.refreshToken");
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));
    }

    @Test
    void refreshRejectsUnknownToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String email = uniqueEmail();
        String refreshToken = registerRefreshToken(email);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REVOKED_REFRESH_TOKEN"));
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sokha\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private void registerWithPhone(String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sokha\",\"phoneNumber\":\"" + phone + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
    }

    private String registerRefreshToken(String email) throws Exception {
        register(email);
        return loginResponse(email).refreshToken;
    }

    private Tokens loginResponse(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new Tokens(
                JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken"),
                JsonPath.read(result.getResponse().getContentAsString(), "$.data.refreshToken"));
    }

    private String login(String email) throws Exception {
        return loginResponse(email).accessToken;
    }

    private void createUser(String email, String roleName) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User();
        user.setFullName("Admin");
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);
        user.addRole(role);
        userRepository.save(user);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private String uniquePhoneNumber() {
        return "+855" + (10_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 90_000_000L));
    }

    private String uniqueSlug() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Tokens(String accessToken, String refreshToken) {
    }
}
