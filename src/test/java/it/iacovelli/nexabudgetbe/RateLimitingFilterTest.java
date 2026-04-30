package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for rate limiting on auth endpoints.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class RateLimitingFilterTest {

        @Autowired
        private WebApplicationContext context;

        private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

        @BeforeEach
        void setUp() {
                mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
                userRepository.deleteAll();
        }

        @Test
        void testRateLimitingOnLoginEndpoint() throws Exception {
                String loginPayload = "{\"username\": \"testuser\", \"password\": \"TestPassword123!\"}";

                for (int i = 0; i < 10; i++) {
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType("application/json")
                                        .content(loginPayload))
                                        .andExpect(result -> {
                                                int status = result.getResponse().getStatus();
                                                assert status == 401 || status == 200 || status == 429;
                                        });
                }

                mockMvc.perform(post("/api/auth/login")
                                .contentType("application/json")
                                .content(loginPayload))
                                .andExpect(status().isTooManyRequests());
        }

        @Test
        void testRateLimitingOnRegisterEndpoint() throws Exception {
                String registerPayload = "{\"username\": \"newuser\", \"email\": \"new@example.com\", \"password\": \"NewPassword123!\", \"defaultCurrency\": \"EUR\"}";

                for (int i = 0; i < 10; i++) {
                        mockMvc.perform(post("/api/auth/register")
                                        .contentType("application/json")
                                        .content(registerPayload))
                                        .andExpect(result -> {
                                                int status = result.getResponse().getStatus();
                                                assert status == 400 || status == 201 || status == 429;
                                        });
                }

                mockMvc.perform(post("/api/auth/register")
                                .contentType("application/json")
                                .content(registerPayload))
                                .andExpect(status().isTooManyRequests());
        }

        @Test
        void testNoRateLimitingOnOtherEndpoints() throws Exception {
                mockMvc.perform(post("/api/accounts")
                                .contentType("application/json")
                                .header("Authorization", "Bearer invalid-token")
                                .content("{}"))
                                .andExpect(result -> {
                                        int status = result.getResponse().getStatus();
                                        assert status == 401 || status == 403;
                                });
        }
}
