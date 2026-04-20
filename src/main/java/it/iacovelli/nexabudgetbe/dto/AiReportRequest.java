package it.iacovelli.nexabudgetbe.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Schema(description = "Request object to start an AI financial report generation")
public record AiReportRequest(
        @NotNull(message = "Start date is required")
        @Schema(description = "Start date of the reporting period", example = "2023-01-01")
        LocalDate startDate,

        @NotNull(message = "End date is required")
        @Schema(description = "End date of the reporting period", example = "2023-12-31")
        LocalDate endDate
) {}
