package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class TransactionDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionRequest {
        @NotNull(message = "L'ID del conto è obbligatorio")
        private UUID accountId;

        private UUID categoryId;

        @NotNull(message = "L'importo è obbligatorio")
        @Positive(message = "L'importo deve essere positivo")
        private BigDecimal amount;

        @NotNull(message = "Il tipo di transazione è obbligatorio")
        private TransactionType type;

        @NotBlank(message = "La descrizione è obbligatoria")
        private String description;

        private LocalDate date;

        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRequest {
        @NotNull(message = "L'ID del conto di origine è obbligatorio")
        private UUID sourceAccountId;

        @NotNull(message = "L'ID del conto di destinazione è obbligatorio")
        private UUID destinationAccountId;

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
        private UUID sourceTransactionId;

        @NotNull(message = "L'ID della transazione destinazione è obbligatorio")
        private UUID destinationTransactionId;
    }


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionResponse {
        private UUID id;
        private UUID accountId;
        private String accountName;
        private UUID categoryId;
        private String categoryName;
        private BigDecimal amount;
        private TransactionType type;
        private String description;
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;
        private String note;
        private String transferId;
    }
}
