package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.TransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class BudgetDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetRequest {
        @NotNull(message = "L'ID della categoria è obbligatorio")
        private UUID categoryId;

        @NotNull(message = "Il limite di budget è obbligatorio")
        @Positive(message = "Il limite di budget deve essere positivo")
        private BigDecimal limit;

        @NotNull(message = "La data di inizio è obbligatoria")
        private LocalDate startDate;

        private LocalDate endDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetResponse {
        private UUID id;
        private UUID categoryId;
        private String categoryName;
        private TransactionType categoryType;
        private BigDecimal limit;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageResponse {
        private UUID budgetId;
        private String categoryName;
        private BigDecimal limit;
        private BigDecimal spent;
        private BigDecimal remaining;
        private double percentageUsed;
    }
}
