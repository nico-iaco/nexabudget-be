package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.ChatDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "Chatbot Finanziario", description = "Conversazione con l'assistente AI per le finanze personali")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @Operation(summary = "Invia messaggio", description = "Invia un messaggio al chatbot. Se sessionId è null, crea una nuova sessione. Ritorna la risposta e il sessionId da usare per i messaggi successivi.")
    public ResponseEntity<ChatDto.ChatResponse> chat(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody ChatDto.ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(currentUser, request));
    }

    @GetMapping("/sessions")
    @Operation(summary = "Lista sessioni", description = "Restituisce tutte le sessioni di chat dell'utente, ordinate per ultima attività.")
    public ResponseEntity<List<ChatDto.ChatSessionSummary>> listSessions(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(chatService.listSessions(currentUser));
    }

    @GetMapping("/sessions/{id}/messages")
    @Operation(summary = "Messaggi sessione", description = "Restituisce la cronologia completa di una sessione di chat.")
    public ResponseEntity<List<ChatDto.ChatMessageDto>> getMessages(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id) {
        return ResponseEntity.ok(chatService.getMessages(currentUser, id));
    }

    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "Elimina sessione", description = "Elimina una sessione di chat e tutti i relativi messaggi.")
    public ResponseEntity<Void> deleteSession(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id) {
        chatService.deleteSession(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
