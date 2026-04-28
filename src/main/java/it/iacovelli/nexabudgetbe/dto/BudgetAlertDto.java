package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import it.iacovelli.nexabudgetbe.model.RecurrenceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class BudgetAlertDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetAlertRequest {
        @NotNull(message = "Il template è obbligatorio")
        private UUID templateId;

        @NotNull(message = "La soglia è obbligatoria")
        @Min(value = 1, message = "La soglia deve essere almeno 1%")
        @Max(value = 100, message = "La soglia non può superare il 100%")
        private Integer thresholdPercentage;

        private Boolean active;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetAlertResponse {
        private UUID id;
        private UUID templateId;
        private UUID categoryId;
        private String categoryName;
        private BigDecimal budgetLimit;
        private RecurrenceType recurrenceType;
        private Integer thresholdPercentage;
        private Boolean active;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        private LocalDateTime lastNotifiedAt;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        private LocalDateTime createdAt;
    }
}
