package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.RecurrenceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

public class BudgetTemplateDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetTemplateRequest {
        @NotNull(message = "L'ID della categoria è obbligatorio")
        private UUID categoryId;

        @NotNull(message = "Il limite di budget è obbligatorio")
        @Positive(message = "Il limite di budget deve essere positivo")
        private BigDecimal budgetLimit;

        @NotNull(message = "Il tipo di ricorrenza è obbligatorio")
        private RecurrenceType recurrenceType;

        private Boolean active;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetTemplateResponse {
        private UUID id;
        private UUID categoryId;
        private String categoryName;
        private BigDecimal budgetLimit;
        private RecurrenceType recurrenceType;
        private Boolean active;
    }
}
