package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.ApiKeyDto;
import it.iacovelli.nexabudgetbe.model.ApiKey;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public ApiKeyDto.CreateApiKeyResponse createApiKey(ApiKeyDto.CreateApiKeyRequest request, User user) {
        // Generate a cryptographically secure random key (32 bytes → 43-char base64url)
        byte[] rawBytes = new byte[32];
        new SecureRandom().nextBytes(rawBytes);
        String plainKey = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);
        String keyHash = sha256(plainKey);

        ApiKey apiKey = ApiKey.builder()
                .user(user)
                .name(request.getName())
                .keyHash(keyHash)
                .scopes(request.getScopes())
                .expiresAt(request.getExpiresAt())
                .build();

        ApiKey saved = apiKeyRepository.save(apiKey);
        logger.info("Nuova API key creata: id={} name='{}' user={}", saved.getId(), saved.getName(), user.getUsername());

        return ApiKeyDto.CreateApiKeyResponse.builder()
                .id(saved.getId())
                .name(saved.getName())
                .scopes(saved.getScopes())
                .expiresAt(saved.getExpiresAt())
                .createdAt(saved.getCreatedAt())
                .key(plainKey)
                .build();
    }

    @Transactional(readOnly = true)
    public List<ApiKeyDto.ApiKeyResponse> getApiKeys(User user) {
        return apiKeyRepository.findByUser(user).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ApiKeyDto.ApiKeyResponse updateApiKey(UUID id, ApiKeyDto.UpdateApiKeyRequest request, User user) {
        ApiKey apiKey = apiKeyRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key non trovata"));

        if (request.getName() != null) apiKey.setName(request.getName());
        if (request.getScopes() != null) apiKey.setScopes(request.getScopes());
        if (request.getExpiresAt() != null) apiKey.setExpiresAt(request.getExpiresAt());
        if (request.getActive() != null) apiKey.setActive(request.getActive());

        return toResponse(apiKeyRepository.save(apiKey));
    }

    @Transactional
    public void deleteApiKey(UUID id, User user) {
        ApiKey apiKey = apiKeyRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key non trovata"));
        apiKeyRepository.delete(apiKey);
        logger.info("API key eliminata: id={} user={}", id, user.getUsername());
    }

    /**
     * Used by the authentication filter to look up a key by its plaintext value.
     * Updates lastUsedAt and validates expiry / active state.
     */
    @Transactional
    public User authenticateByKey(String plainKey) {
        String hash = sha256(plainKey);
        ApiKey apiKey = apiKeyRepository.findByKeyHash(hash).orElse(null);
        if (apiKey == null || !Boolean.TRUE.equals(apiKey.getActive())) {
            return null;
        }
        if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        apiKey.setLastUsedAt(LocalDateTime.now());
        apiKeyRepository.save(apiKey);
        return apiKey.getUser();
    }

    private ApiKeyDto.ApiKeyResponse toResponse(ApiKey apiKey) {
        return ApiKeyDto.ApiKeyResponse.builder()
                .id(apiKey.getId())
                .name(apiKey.getName())
                .scopes(apiKey.getScopes())
                .active(apiKey.getActive())
                .expiresAt(apiKey.getExpiresAt())
                .lastUsedAt(apiKey.getLastUsedAt())
                .createdAt(apiKey.getCreatedAt())
                .build();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }
}
