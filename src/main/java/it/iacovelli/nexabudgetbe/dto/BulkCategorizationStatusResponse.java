package it.iacovelli.nexabudgetbe.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Stato di avanzamento del job di categorizzazione massiva via AI")
public record BulkCategorizationStatusResponse(
        @Schema(description = "ID univoco del job")
        UUID jobId,

        @Schema(description = "Stato del job: PENDING, IN_PROGRESS, COMPLETED, FAILED")
        String status,

        @Schema(description = "Numero totale di transazioni da categorizzare")
        int total,

        @Schema(description = "Numero di transazioni già processate")
        int processed,

        @Schema(description = "Numero di transazioni effettivamente categorizzate dall'AI")
        int categorized
) {}
