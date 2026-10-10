package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.PriceSource;

import java.util.Optional;

/** Fonte di quotazioni di mercato. Un provider non configurato non fa mai fallire l'avvio: risponde empty. */
public interface MarketPriceProvider {

    PriceSource source();

    boolean isConfigured();

    /** Ultima quotazione del simbolo, o empty se non disponibile (errore, rate limit, simbolo sconosciuto). */
    Optional<MarketQuote> getQuote(String symbol);
}
