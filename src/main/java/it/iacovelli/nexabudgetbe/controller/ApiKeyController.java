package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.ApiKeyDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/api-keys")
@Tag(name = "API Keys", description = "Gestione chiavi API per accesso machine-to-machine")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @Operation(summary = "Crea API key", description = "Genera una nuova API key. Il valore della chiave è mostrato una sola volta nella risposta.")
    public ResponseEntity<ApiKeyDto.CreateApiKeyResponse> createApiKey(
            @Valid @RequestBody ApiKeyDto.CreateApiKeyRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(apiKeyService.createApiKey(request, currentUser));
    }

    @GetMapping
    @Operation(summary = "Lista API keys", description = "Elenca le API key dell'utente (senza il valore in chiaro)")
    public ResponseEntity<List<ApiKeyDto.ApiKeyResponse>> getApiKeys(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(apiKeyService.getApiKeys(currentUser));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna API key", description = "Modifica nome, scopes, scadenza o stato attivo di una chiave")
    public ResponseEntity<ApiKeyDto.ApiKeyResponse> updateApiKey(
            @PathVariable UUID id,
            @RequestBody ApiKeyDto.UpdateApiKeyRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(apiKeyService.updateApiKey(id, request, currentUser));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina API key", description = "Elimina permanentemente una API key")
    public ResponseEntity<Void> deleteApiKey(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser) {
        apiKeyService.deleteApiKey(id, currentUser);
        return ResponseEntity.noContent().build();
    }
}
