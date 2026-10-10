package it.iacovelli.nexabudgetbe.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * <p>
 * Gli {@code @Schema} servono al Swagger/OpenAPI: nelle risposte {@code requiredMode = REQUIRED} indica un campo
 * sempre presente e non null, {@code nullable = true} un campo che può essere null (presente nel JSON con valore null).
 */
public class InvestmentDto {

    @Schema(description = "Creazione di un asset da tracciare")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetRequest {
        @Schema(description = "Tipo di strumento. Per BOND la quantità delle operazioni è il valore nominale e il prezzo è in % del nominale",
                example = "ETF")
        @NotNull
        private InvestmentAssetType assetType;
        @Schema(description = "Nome visualizzato", example = "Vanguard FTSE All-World UCITS ETF")
        @NotBlank
        @Size(max = 255)
        private String name;
        @Schema(description = "ISIN (12 caratteri). Il backend ne controlla solo il formato, non che corrisponda al simbolo. Univoco per utente",
                example = "IE00BK5BQT80", nullable = true)
        @Pattern(regexp = "^[A-Za-z]{2}[A-Za-z0-9]{9}[0-9]$", message = "ISIN non valido")
        private String isin;
        @Schema(description = "Ticker nella notazione Yahoo Finance (es. VWCE.DE, ENEL.MI, AAPL). Obbligatorio se priceSource non è MANUAL. Univoco per utente. Non inviare stringhe vuote: ometti il campo o invia null",
                example = "VWCE.DE", nullable = true)
        @Pattern(regexp = "^[A-Za-z0-9.^=_\\-]{1,32}$", message = "Simbolo non valido")
        private String symbol;
        @Schema(description = "Valuta in cui è espresso il prezzo (ISO 4217). Se omessa e c'è un simbolo viene ricavata dalla quotazione; "
                + "se la quotazione non è disponibile la creazione risponde 400 e va indicata esplicitamente", example = "EUR", nullable = true)
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Valuta non valida")
        private String currency;
        @Schema(description = "Fonte del prezzo. Se omessa: YAHOO quando c'è un simbolo, altrimenti MANUAL", nullable = true)
        private PriceSource priceSource;
        @Schema(description = "Prezzo manuale iniziale (obbligazioni: % del nominale)", example = "101.35", nullable = true)
        @Positive
        private BigDecimal manualPrice;
        @Schema(description = "Tasso cedolare annuo in percentuale (3.5 = 3,5%). Solo BOND, ignorato per gli altri tipi",
                example = "3.5", nullable = true)
        @DecimalMin("0")
        @DecimalMax("100")
        private BigDecimal couponRate;
        @Schema(description = "Frequenza della cedola. Solo BOND", nullable = true)
        private CouponFrequency couponFrequency;
        @Schema(description = "Scadenza. Solo BOND", example = "2029-09-01", nullable = true)
        private LocalDate maturityDate;
    }

    /** Come {@link AssetRequest} ma senza valuta: non si cambia con operazioni già registrate. */
    @Schema(description = "Modifica di un asset: sostituisce tutti i campi indicati. La valuta non è modificabile. "
            + "Cambiare simbolo o fonte prezzo azzera l'ultimo prezzo noto")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetUpdateRequest {
        @Schema(description = "Tipo di strumento. Il passaggio da/a BOND con operazioni già registrate risponde 409")
        @NotNull
        private InvestmentAssetType assetType;
        @Schema(description = "Nome visualizzato", example = "Vanguard FTSE All-World UCITS ETF")
        @NotBlank
        @Size(max = 255)
        private String name;
        @Schema(description = "ISIN (12 caratteri). Facoltativo: ometti o invia null per rimuoverlo; la stringa vuota è rifiutata (400)",
                example = "IE00BK5BQT80", nullable = true)
        @Pattern(regexp = "^[A-Za-z]{2}[A-Za-z0-9]{9}[0-9]$", message = "ISIN non valido")
        private String isin;
        @Schema(description = "Ticker Yahoo. Facoltativo solo se priceSource è MANUAL (altrimenti 400). Ometti o invia null per rimuoverlo; la stringa vuota è rifiutata (400)",
                example = "VWCE.DE", nullable = true)
        @Pattern(regexp = "^[A-Za-z0-9.^=_\\-]{1,32}$", message = "Simbolo non valido")
        private String symbol;
        @Schema(description = "Fonte del prezzo")
        @NotNull
        private PriceSource priceSource;
        @Schema(description = "Tasso cedolare annuo in % (solo BOND)", example = "3.5", nullable = true)
        @DecimalMin("0")
        @DecimalMax("100")
        private BigDecimal couponRate;
        @Schema(description = "Frequenza della cedola (solo BOND)", nullable = true)
        private CouponFrequency couponFrequency;
        @Schema(description = "Scadenza (solo BOND)", example = "2029-09-01", nullable = true)
        private LocalDate maturityDate;
    }

    @Schema(description = "Asset di investimento tracciato dall'utente")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetResponse {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID id;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private InvestmentAssetType assetType;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Vanguard FTSE All-World UCITS ETF")
        private String name;
        @Schema(example = "IE00BK5BQT80", nullable = true)
        private String isin;
        @Schema(description = "Ticker Yahoo; null per gli asset solo manuali", example = "VWCE.DE", nullable = true)
        private String symbol;
        @Schema(description = "Valuta del prezzo dell'asset (non modificabile)", requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR")
        private String currency;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private PriceSource priceSource;
        @Schema(description = "Prezzo inserito a mano. Usato come prezzo se priceSource=MANUAL, altrimenti come ultima riserva",
                nullable = true)
        private BigDecimal manualPrice;
        @Schema(nullable = true)
        private LocalDateTime manualPriceAt;
        @Schema(description = "Tasso cedolare annuo in % (solo BOND)", nullable = true)
        private BigDecimal couponRate;
        @Schema(nullable = true)
        private CouponFrequency couponFrequency;
        @Schema(nullable = true)
        private LocalDate maturityDate;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDateTime createdAt;
    }

    @Schema(description = "Prezzo manuale di un asset")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManualPriceRequest {
        @Schema(description = "Prezzo nella valuta dell'asset (obbligazioni: % del nominale)", example = "101.35")
        @NotNull
        @Positive
        private BigDecimal price;
    }

    @Schema(description = "Operazione su un asset. BUY/SELL richiedono quantity e price; DIVIDEND/COUPON richiedono amount. "
            + "Il backend accetta qualunque tipo su qualunque asset")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationRequest {
        @Schema(example = "BUY")
        @NotNull
        private InvestmentOperationType type;
        @Schema(description = "Data dell'operazione; non può essere nel futuro (400)", example = "2026-09-01")
        @NotNull
        private LocalDate operationDate;
        @Schema(description = "BUY/SELL: numero di quote (obbligazioni: valore nominale, es. 10000). Deve essere > 0",
                example = "10", nullable = true)
        @Positive
        private BigDecimal quantity;
        @Schema(description = "BUY/SELL: prezzo unitario nella valuta dell'asset (obbligazioni: % del nominale, es. 98.5). Deve essere > 0",
                example = "100.5", nullable = true)
        @Positive
        private BigDecimal price;
        @Schema(description = "DIVIDEND/COUPON: importo NETTO incassato nella valuta dell'asset. Deve essere > 0 (0 e negativi sono rifiutati)",
                example = "12.50", nullable = true)
        @Positive
        private BigDecimal amount;
        @Schema(description = "Commissioni (>= 0). Fanno parte del costo di carico (BUY) e riducono l'incasso (SELL). Default 0",
                example = "1.5", nullable = true)
        @PositiveOrZero
        private BigDecimal fees;
        @Schema(nullable = true)
        @Size(max = 500)
        private String notes;
    }

    @Schema(description = "Operazione registrata. Importi nella valuta dell'asset")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperationResponse {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID id;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID assetId;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String assetName;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private InvestmentOperationType type;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate operationDate;
        @Schema(description = "Solo BUY/SELL (null per DIVIDEND/COUPON)", nullable = true)
        private BigDecimal quantity;
        @Schema(description = "Solo BUY/SELL (null per DIVIDEND/COUPON)", nullable = true)
        private BigDecimal price;
        @Schema(description = "Solo DIVIDEND/COUPON, netto (null per BUY/SELL)", nullable = true)
        private BigDecimal amount;
        @Schema(description = "Commissioni, 0 se assenti", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal fees;
        @Schema(nullable = true)
        private String notes;
    }

    /**
     * Posizione su un asset. Gli importi (costBasis, marketValue, unrealizedPl, realizedPl, income) sono nella
     * valuta del portafoglio, convertiti al cambio corrente; avgPrice e price restano nella valuta
     * dell'asset (obbligazioni: % del nominale). Gli importi convertiti sono null se manca un prezzo o un tasso.
     */
    @Schema(description = "Posizione su un asset. costBasis, marketValue, unrealizedPl, realizedPl e income sono nella valuta del "
            + "portafoglio (cambio corrente); avgPrice e price restano nella valuta dell'asset (obbligazioni: % del nominale). "
            + "I campi nullable sono null se manca un prezzo o un tasso di cambio: mostrali come \"n/d\", non come 0")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PositionResponse {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID assetId;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private InvestmentAssetType assetType;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;
        @Schema(nullable = true)
        private String isin;
        @Schema(nullable = true)
        private String symbol;
        @Schema(description = "Valuta dell'asset", requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR")
        private String currency;
        @Schema(description = "Quote detenute (obbligazioni: nominale). 0 = posizione chiusa", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal quantity;
        @Schema(description = "Prezzo medio di carico, valuta dell'asset (obbligazioni: % del nominale). 0 se la posizione è chiusa",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal avgPrice;
        @Schema(description = "Costo di carico (commissioni incluse), valuta del portafoglio; null senza tasso di cambio", nullable = true)
        private BigDecimal costBasis;
        @Schema(description = "Prezzo attuale, valuta priceCurrency (obbligazioni: % del nominale); null se non disponibile "
                + "o per le posizioni chiuse", nullable = true)
        private BigDecimal price;
        @Schema(nullable = true, example = "EUR")
        private String priceCurrency;
        @Schema(description = "Provider da cui arriva il prezzo", nullable = true)
        private PriceSource priceSource;
        @Schema(nullable = true)
        private LocalDateTime priceAsOf;
        @Schema(description = "true se il provider non ha risposto e si usa l'ultimo prezzo noto o quello manuale",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean stale;
        @Schema(description = "Valore di mercato, valuta del portafoglio. 0 per le posizioni chiuse; null se manca prezzo o cambio "
                + "(la posizione è esclusa dai totali)", nullable = true)
        private BigDecimal marketValue;
        @Schema(description = "Utile/perdita non realizzato (marketValue - costBasis); null se uno dei due manca", nullable = true)
        private BigDecimal unrealizedPl;
        @Schema(description = "Utile/perdita non realizzato in % del costo; null se non calcolabile", nullable = true)
        private BigDecimal unrealizedPlPercent;
        @Schema(description = "Utile/perdita realizzato dalle vendite; null senza tasso di cambio", nullable = true)
        private BigDecimal realizedPl;
        @Schema(description = "Dividendi e cedole incassati; null senza tasso di cambio", nullable = true)
        private BigDecimal income;
        @Schema(nullable = true)
        private BigDecimal couponRate;
        @Schema(nullable = true)
        private CouponFrequency couponFrequency;
        @Schema(nullable = true)
        private LocalDate maturityDate;
    }

    @Schema(description = "Quota di una voce di allocazione")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationItem {
        @Schema(description = "Tipo di asset (ETF, STOCK, ...) o codice valuta, a seconda della lista", requiredMode = Schema.RequiredMode.REQUIRED)
        private String key;
        @Schema(description = "Valore nella valuta del portafoglio", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal value;
        @Schema(description = "Percentuale sul valore totale", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal percent;
    }

    @Schema(description = "Portafoglio investimenti valorizzato. I totali includono solo le posizioni con prezzo e cambio disponibili")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortfolioResponse {
        @Schema(description = "Valuta dei valori aggregati", requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR")
        private String currency;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal totalValue;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal totalCostBasis;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal unrealizedPl;
        @Schema(description = "null se il costo totale è 0", nullable = true)
        private BigDecimal unrealizedPlPercent;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal realizedPl;
        @Schema(description = "Dividendi e cedole incassati", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal income;
        @Schema(description = "false se almeno una posizione aperta non ha prezzo o tasso di cambio ed è esclusa dai totali",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean complete;
        @Schema(description = "Posizioni aperte per prime (per valore decrescente), poi quelle chiuse", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<PositionResponse> positions;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private List<AllocationItem> allocationByType;
        @Schema(description = "Per valuta di quotazione", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<AllocationItem> allocationByCurrency;
    }

    @Schema(description = "Performance degli investimenti in un periodo. Importi nella valuta dell'utente, cambio corrente")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceResponse {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate startDate;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate endDate;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR")
        private String currency;
        @Schema(description = "Esborsi per acquisti nel periodo (commissioni incluse)", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal invested;
        @Schema(description = "Incassi netti dalle vendite nel periodo", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal divested;
        @Schema(description = "Utile/perdita realizzato dalle vendite nel periodo", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal realizedPl;
        @Schema(description = "Dividendi e cedole del periodo", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal income;
        @Schema(description = "Valore del portafoglio a inizio periodo (snapshot entro 7 giorni; 0 se non c'erano operazioni prima); "
                + "null se non ricostruibile", nullable = true)
        private BigDecimal startValue;
        @Schema(description = "Valore a fine periodo (portafoglio attuale se endDate è oggi o futura, altrimenti snapshot); "
                + "null se non disponibile o portafoglio incompleto", nullable = true)
        private BigDecimal endValue;
        @Schema(description = "(endValue - startValue) - (invested - divested) + income; null se startValue o endValue mancano",
                nullable = true)
        private BigDecimal totalGain;
    }

    @Schema(description = "Valore del portafoglio in un giorno (snapshot notturno)")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryPoint {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate date;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal marketValue;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal costBasis;
    }

    @Schema(description = "Storico del portafoglio. Gli snapshot esistono solo dal giorno di attivazione della funzione: points può essere vuoto")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryResponse {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR")
        private String currency;
        @Schema(description = "In ordine di data crescente", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<HistoryPoint> points;
    }

    @Schema(description = "Strumento trovato dalla ricerca. Non contiene ISIN né valuta: la valuta viene ricavata dal backend alla creazione "
            + "dell'asset. Un ISIN può avere più listing (borse/valute diverse)")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        @Schema(description = "Ticker da usare come symbol dell'asset", requiredMode = Schema.RequiredMode.REQUIRED, example = "VWCE.DE")
        private String symbol;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;
        @Schema(description = "Borsa (nome o codice, può essere vuoto)", requiredMode = Schema.RequiredMode.REQUIRED, example = "XETRA")
        private String exchange;
        @Schema(description = "Tipo suggerito, modificabile dall'utente", requiredMode = Schema.RequiredMode.REQUIRED)
        private InvestmentAssetType suggestedType;
        @Schema(description = "YAHOO oppure OPENFIGI (risultati non verificati)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String provider;
    }
}
