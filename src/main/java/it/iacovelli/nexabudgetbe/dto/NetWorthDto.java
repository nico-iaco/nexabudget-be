package it.iacovelli.nexabudgetbe.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class NetWorthDto {

    /**
     * Patrimonio netto = liquidità (conti) + crypto + investimenti, nella valuta richiesta.
     * Una componente non calcolabile è null e il totale la esclude (complete = false, motivo in warnings).
     */
    @Schema(description = "Patrimonio netto = liquidità (conti) + crypto + investimenti. Una componente non calcolabile è null: "
            + "il totale la esclude, complete è false e il motivo è in warnings")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetWorthResponse {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR")
        private String currency;
        @Schema(description = "Somma delle sole componenti disponibili", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal total;
        @Schema(description = "Saldo dei conti convertito; null se non convertibile nella valuta richiesta", nullable = true)
        private BigDecimal liquidity;
        @Schema(description = "Valore crypto; null se non calcolabile o non convertibile (mai in una valuta diversa da quella richiesta)", nullable = true)
        private BigDecimal crypto;
        @Schema(description = "Valore degli investimenti (parziale se complete è false); null se non calcolabile", nullable = true)
        private BigDecimal investments;
        @Schema(description = "Quota sul totale; può essere negativa (conti in rosso); null se la componente manca o il totale non è positivo", nullable = true)
        private BigDecimal liquidityPercent;
        @Schema(nullable = true)
        private BigDecimal cryptoPercent;
        @Schema(nullable = true)
        private BigDecimal investmentsPercent;
        @Schema(description = "false se una componente manca o è parziale: il totale è parziale", requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean complete;
        @Schema(description = "Motivi per cui il valore è parziale o ci sono avvisi (anche il possibile doppio conteggio)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private List<String> warnings;
        // Saldo dei conti di tipo INVESTIMENTO (già compreso nella liquidità). Se il broker è tracciato sia come
        // conto sia con gli asset, quel valore è contato due volte: possibleDoubleCounting lo segnala.
        @Schema(description = "Saldo dei conti di tipo INVESTIMENTO (già compreso nella liquidità); null se non calcolabile", nullable = true)
        private BigDecimal investmentAccountsBalance;
        @Schema(description = "true se esistono sia conti INVESTIMENTO sia asset di investimento: se sono lo stesso broker il valore è contato due volte",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean possibleDoubleCounting;
    }

    @Schema(description = "Patrimonio a fine mese. Crypto e investimenti hanno storico solo dal primo snapshot giornaliero")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetWorthPoint {
        @Schema(description = "Ultimo giorno del mese (per il mese in corso, oggi)", requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate date;
        @Schema(nullable = true)
        private BigDecimal liquidity;
        @Schema(description = "null per i mesi precedenti al primo snapshot crypto (contano come 0 nel totale)", nullable = true)
        private BigDecimal crypto;
        @Schema(description = "null per i mesi precedenti al primo snapshot investimenti (contano come 0 nel totale)", nullable = true)
        private BigDecimal investments;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal total;
    }

    @Schema(description = "Serie mensile del patrimonio netto (liquidità + crypto + investimenti)")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetWorthHistoryResponse {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR")
        private String currency;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private List<NetWorthPoint> points;
        // Le crypto hanno storico solo dal primo snapshot giornaliero: i mesi precedenti hanno crypto = null
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean cryptoIncludedInHistory;
    }
}
