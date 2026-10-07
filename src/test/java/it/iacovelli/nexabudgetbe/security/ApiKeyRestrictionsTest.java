package it.iacovelli.nexabudgetbe.security;

import it.iacovelli.nexabudgetbe.controller.ApiKeyController;
import it.iacovelli.nexabudgetbe.controller.UserController;
import it.iacovelli.nexabudgetbe.dto.ApiKeyDto;
import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.ApiKeyService;
import it.iacovelli.nexabudgetbe.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Una API key non deve poter creare nuove chiavi né cambiare email/password dell'account. */
class ApiKeyRestrictionsTest {

    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final UserService userService = mock(UserService.class);
    private final ApiKeyController apiKeyController = new ApiKeyController(apiKeyService);
    private final UserController userController = new UserController(userService);

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().username("apikeyuser").email("apikey@example.com").passwordHash("hash").build();
        user.setId(UUID.randomUUID());
        when(userService.getUserById(user.getId())).thenReturn(Optional.of(user));
        when(userService.updateUserProfile(any(), any(), any(), any(), any())).thenReturn(user);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWithApiKey() {
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(user));
    }

    private void authenticateWithJwt() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void apiKey_cannotCreateApiKeys() {
        authenticateWithApiKey();
        ApiKeyDto.CreateApiKeyRequest request = ApiKeyDto.CreateApiKeyRequest.builder().name("nuova").build();

        assertThrows(AccessDeniedException.class, () -> apiKeyController.createApiKey(request, user));
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void jwtSession_canCreateApiKeys() {
        authenticateWithJwt();
        ApiKeyDto.CreateApiKeyRequest request = ApiKeyDto.CreateApiKeyRequest.builder().name("nuova").build();

        apiKeyController.createApiKey(request, user);

        verify(apiKeyService).createApiKey(request, user);
    }

    @Test
    void apiKey_cannotChangeEmailOrPassword() {
        authenticateWithApiKey();

        UserDto.UpdateUserRequest emailChange = UserDto.UpdateUserRequest.builder().email("altro@example.com").build();
        UserDto.UpdateUserRequest passwordChange = UserDto.UpdateUserRequest.builder().password("NuovaPassword1!").build();

        assertThrows(AccessDeniedException.class, () -> userController.updateUser(user, emailChange));
        assertThrows(AccessDeniedException.class, () -> userController.updateUser(user, passwordChange));
        verify(userService, never()).updateUserProfile(any(), any(), any(), any(), any());
    }

    @Test
    void apiKey_canChangeOtherProfileFields() {
        authenticateWithApiKey();
        UserDto.UpdateUserRequest currencyChange = UserDto.UpdateUserRequest.builder().defaultCurrency("USD").build();

        userController.updateUser(user, currencyChange);

        verify(userService).updateUserProfile(user, null, null, null, "USD");
    }
}
