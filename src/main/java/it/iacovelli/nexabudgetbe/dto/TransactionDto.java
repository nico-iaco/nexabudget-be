package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class TransactionDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionRequest {
        @NotNull(message = "L'ID del conto è obbligatorio")
        private Long accountId;

        private Long categoryId;

        @NotNull(message = "L'importo è obbligatorio")
        @Positive(message = "L'importo deve essere positivo")
        private BigDecimal importo;

        @NotNull(message = "Il tipo di transazione è obbligatorio")
        private TransactionType type;

        @NotBlank(message = "La descrizione è obbligatoria")
        private String descrizione;

        private LocalDate data;

        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRequest {
        @NotNull(message = "L'ID del conto di origine è obbligatorio")
        private Long sourceAccountId;

        @NotNull(message = "L'ID del conto di destinazione è obbligatorio")
        private Long destinationAccountId;

        @NotNull(message = "L'importo è obbligatorio")
        @Positive(message = "L'importo deve essere positivo")
        private BigDecimal amount;

        @NotNull(message = "La data del trasferimento deve essere obbligatoria")
        private LocalDate transferDate;

        @NotBlank(message = "La descrizione è obbligatoria")
        private String description;

        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConvertToTransferRequest {

        @NotNull(message = "L'ID della transazione sorgente è obbligatorio")
        private Long sourceTransactionId;

        @NotNull(message = "L'ID della transazione destinazione è obbligatorio")
        private Long destinationTransactionId;
    }


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionResponse {
        private Long id;
        private Long accountId;
        private String accountName;
        private Long categoryId;
        private String categoryName;
        private BigDecimal importo;
        private TransactionType type;
        private String descrizione;
        private LocalDate data;
        private String note;
        private String transferId;
    }
}
