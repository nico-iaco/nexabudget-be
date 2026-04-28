package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import it.iacovelli.nexabudgetbe.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class AccountDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountRequest {
        @NotBlank(message = "Il nome è obbligatorio")
        private String name;

        @NotNull(message = "Il tipo di conto è obbligatorio")
        private AccountType type;

        @PositiveOrZero(message = "Il saldo iniziale deve essere maggiore o uguale a zero")
        private BigDecimal starterBalance;

        @NotBlank(message = "La valuta è obbligatoria")
        private String currency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountResponse {
        private UUID id;
        private String name;
        private AccountType type;
        private BigDecimal actualBalance;
        private String currency;
        private boolean isLinkedToExternal;
        private boolean isSynchronizing;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrashAccountResponse {
        private UUID id;
        private String name;
        private AccountType type;
        private String currency;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        private LocalDateTime deletedAt;
    }
}
