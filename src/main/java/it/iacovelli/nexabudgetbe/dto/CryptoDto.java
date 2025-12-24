package it.iacovelli.nexabudgetbe.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import it.iacovelli.nexabudgetbe.model.HoldingSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class CryptoDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManualHoldingRequest {
        @NotBlank
        private String symbol;
        @NotNull
        @Positive
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateHoldingRequest {
        @NotNull
        @Positive
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BinanceKeysRequest {
        @NotBlank
        private String apiKey;
        @NotBlank
        private String apiSecret;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortfolioValueResponse {
        private BigDecimal totalValue;
        private String currency; // Es. "USD", "EUR", "GBP"
        private List<AssetValue> assets;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetValue {
        private UUID id;
        private HoldingSource source;
        private String symbol;
        private BigDecimal amount;
        private BigDecimal price; // Prezzo nella valuta specificata
        private BigDecimal value; // Valore totale nella valuta specificata
    }
}
