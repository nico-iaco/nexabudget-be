package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

public class CategoryDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryRequest {
        @NotBlank(message = "Il nome è obbligatorio")
        private String name;

        @NotNull(message = "Il tipo di transazione è obbligatorio")
        private TransactionType transactionType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryResponse {
        private UUID id;
        private String name;
        private TransactionType transactionType;
        private Boolean isDefault;
    }
}
