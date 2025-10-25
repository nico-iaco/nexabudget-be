package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.AccountType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

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
        private Long id;
        private String name;
        private AccountType type;
        private BigDecimal actualBalance;
        private String currency;
        private String createdAt;
    }
}
