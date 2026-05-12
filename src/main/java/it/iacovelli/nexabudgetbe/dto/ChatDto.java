package it.iacovelli.nexabudgetbe.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ChatDto {

    public record ChatRequest(
            UUID sessionId,
            @NotBlank(message = "Il messaggio non può essere vuoto")
            @Size(max = 4000, message = "Il messaggio non può superare i 4000 caratteri")
            String message
    ) {}

    public record ChatResponse(
            UUID sessionId,
            String reply,
            List<String> toolsUsed
    ) {}

    public record ChatSessionSummary(
            UUID id,
            String title,
            LocalDateTime updatedAt,
            int messageCount
    ) {}

    public record ChatMessageDto(
            UUID id,
            String role,
            String content,
            LocalDateTime createdAt,
            String toolName
    ) {}
}
