package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.CouponFrequency;
import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import it.iacovelli.nexabudgetbe.model.InvestmentOperationType;
import it.iacovelli.nexabudgetbe.model.PriceSource;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO del tracking investimenti. Le classi di risposta cachate in Redis ({@link PortfolioResponse} e figli) sono
 * non final per via del default typing {@code NON_FINAL} del serializzatore della cache.
 */
public class InvestmentDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetRequest {
        @NotNull
        private InvestmentAssetType assetType;
        @NotBlank
        @Size(max = 255)
        private String name;
        @Pattern(regexp = "^[A-Za-z]{2}[A-Za-z0-9]{9}[0-9]$", message = "ISIN non valido")
        private String isin;
        // Ticker nella notazione del provider (Yahoo: VWCE.DE, ENEL.MI, AAPL)
        @Pattern(regexp = "^[A-Za-z0-9.^=_\\-]{1,32}$", message = "Simbolo non valido")
        private String symbol;
        // Se assente e c'è un simbolo, viene ricavata dalla quotazione
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Valuta non valida")
        private String currency;
        // Se assente: YAHOO quando c'è un simbolo, altrimenti MANUAL
        private PriceSource priceSource;
        @Positive
        private BigDecimal manualPrice;
        // Tasso cedolare annuo in percentuale (es. 3.5 = 3,5%)
        @DecimalMin("0")
        @DecimalMax("100")
        private BigDecimal couponRate;
        private CouponFrequency couponFrequency;
        private LocalDate maturityDate;
    }

    /** Come {@link AssetRequest} ma senza valuta: non si cambia con operazioni già registrate. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetUpdateRequest {
        @NotNull
        private InvestmentAssetType assetType;
        @NotBlank
        @Size(max = 255)
        private String name;
        @Pattern(regexp = "^[A-Za-z]{2}[A-Za-z0-9]{9}[0-9]$", message = "ISIN non valido")
        private String isin;
        @Pattern(regexp = "^[A-Za-z0-9.^=_\\-]{1,32}$", message = "Simbolo non valido")
        private String symbol;
        @NotNull
        private PriceSource priceSource;
        @DecimalMin("0")
        @DecimalMax("100")
        private BigDecimal couponRate;
        private CouponFrequency couponFrequency;
        private LocalDate maturityDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetResponse {
        private UUID id;
        private InvestmentAssetType assetType;
        private String name;
        private String isin;
        private String symbol;
        private String currency;
        private PriceSource priceSource;
        private BigDecimal manualPrice;
        private LocalDateTime manualPriceAt;
        private BigDecimal couponRate;
        private CouponFrequency couponFrequency;
        private LocalDate maturityDate;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManualPriceRequest {
        // Obbligazioni: % del nominale (es. 101.35)
        @NotNull
        @Positive
        private BigDecimal price;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationRequest {
        @NotNull
        private InvestmentOperationType type;
        @NotNull
        private LocalDate operationDate;
        // BUY/SELL: quote (obbligazioni: valore nominale)
        @Positive
        private BigDecimal quantity;
        // BUY/SELL: prezzo unitario nella valuta dell'asset (obbligazioni: % del nominale)
        @Positive
        private BigDecimal price;
        // DIVIDEND/COUPON: importo netto incassato nella valuta dell'asset
        @Positive
        private BigDecimal amount;
        @PositiveOrZero
        private BigDecimal fees;
        @Size(max = 500)
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResponse {
        private UUID id;
        private UUID assetId;
        private String assetName;
        private InvestmentOperationType type;
        private LocalDate operationDate;
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal amount;
        private BigDecimal fees;
        private String notes;
    }

    /**
     * Posizione su un asset. Gli importi (costBasis, marketValue, unrealizedPl, realizedPl, income) sono nella
     * valuta del portafoglio, convertiti al cambio corrente; avgPrice e price restano nella valuta
     * dell'asset (obbligazioni: % del nominale). Gli importi convertiti sono null se manca un prezzo o un tasso.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PositionResponse {
        private UUID assetId;
        private InvestmentAssetType assetType;
        private String name;
        private String isin;
        private String symbol;
        private String currency;
        private BigDecimal quantity;
        private BigDecimal avgPrice;
        private BigDecimal costBasis;
        private BigDecimal price;
        private String priceCurrency;
        private PriceSource priceSource;
        private LocalDateTime priceAsOf;
        // true se il prezzo è l'ultimo noto/manuale perché il provider non ha risposto
        private boolean stale;
        private BigDecimal marketValue;
        private BigDecimal unrealizedPl;
        private BigDecimal unrealizedPlPercent;
        private BigDecimal realizedPl;
        private BigDecimal income;
        private BigDecimal couponRate;
        private CouponFrequency couponFrequency;
        private LocalDate maturityDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationItem {
        private String key;
        private BigDecimal value;
        private BigDecimal percent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortfolioResponse {
        private String currency;
        private BigDecimal totalValue;
        private BigDecimal totalCostBasis;
        private BigDecimal unrealizedPl;
        private BigDecimal unrealizedPlPercent;
        private BigDecimal realizedPl;
        private BigDecimal income;
        // false se almeno una posizione aperta non ha prezzo o tasso di cambio: i totali la escludono
        private boolean complete;
        private List<PositionResponse> positions;
        private List<AllocationItem> allocationByType;
        private List<AllocationItem> allocationByCurrency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceResponse {
        private LocalDate startDate;
        private LocalDate endDate;
        private String currency;
        // Esborsi per acquisti (commissioni incluse) e incassi netti dalle vendite nel periodo
        private BigDecimal invested;
        private BigDecimal divested;
        private BigDecimal realizedPl;
        private BigDecimal income;
        // Valore di portafoglio a inizio e fine periodo; null se non ricostruibile (nessuno snapshot)
        private BigDecimal startValue;
        private BigDecimal endValue;
        // (endValue - startValue) - (invested - divested) + income; null se uno dei due valori manca
        private BigDecimal totalGain;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryPoint {
        private LocalDate date;
        private BigDecimal marketValue;
        private BigDecimal costBasis;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryResponse {
        private String currency;
        private List<HistoryPoint> points;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        private String symbol;
        private String name;
        private String exchange;
        private InvestmentAssetType suggestedType;
        private String provider;
    }
}
