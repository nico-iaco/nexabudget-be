package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Strumento trovato dalla ricerca (ticker, nome o ISIN). Non final: viene cachato in Redis. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentSearchResult {
    private String symbol;
    private String name;
    private String exchange;
    // Tipo suggerito a partire dalla classificazione del provider; l'utente può cambiarlo
    private InvestmentAssetType suggestedType;
    // Provider che ha prodotto il risultato (YAHOO / OPENFIGI)
    private String provider;
}
