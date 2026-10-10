package it.iacovelli.nexabudgetbe.dto;

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
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetWorthResponse {
        private String currency;
        private BigDecimal total;
        private BigDecimal liquidity;
        private BigDecimal crypto;
        private BigDecimal investments;
        private BigDecimal liquidityPercent;
        private BigDecimal cryptoPercent;
        private BigDecimal investmentsPercent;
        private boolean complete;
        private List<String> warnings;
        // Saldo dei conti di tipo INVESTIMENTO (già compreso nella liquidità). Se il broker è tracciato sia come
        // conto sia con gli asset, quel valore è contato due volte: possibleDoubleCounting lo segnala.
        private BigDecimal investmentAccountsBalance;
        private boolean possibleDoubleCounting;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetWorthPoint {
        // Ultimo giorno del mese
        private LocalDate date;
        private BigDecimal liquidity;
        private BigDecimal crypto;
        private BigDecimal investments;
        private BigDecimal total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetWorthHistoryResponse {
        private String currency;
        private List<NetWorthPoint> points;
        // Le crypto hanno storico solo dal primo snapshot giornaliero: i mesi precedenti hanno crypto = null
        private boolean cryptoIncludedInHistory;
    }
}
