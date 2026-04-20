package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
@Schema(description = "Response object containing the status of an AI report job")
public record AiReportStatusResponse(
        @Schema(description = "The unique identifier of the job")
        UUID jobId,

        @Schema(description = "The current status of the job (PENDING, COMPLETED, FAILED)", example = "COMPLETED")
        String status,

        @Schema(description = "The AI generated markdown report, present only if COMPLETED")
        String content
) {}
