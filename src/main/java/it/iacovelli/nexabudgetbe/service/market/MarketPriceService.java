package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.InvestmentAsset;
import it.iacovelli.nexabudgetbe.model.PriceSource;
import it.iacovelli.nexabudgetbe.repository.InvestmentAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Risolve il prezzo corrente di un asset. Ordine di fallback:
 * <ol>
 *   <li>{@code MANUAL}: solo il prezzo inserito dall'utente;</li>
 *   <li>altrimenti il provider preferito dell'asset, poi l'altro provider (se configurato);</li>
 *   <li>l'ultimo prezzo live persistito ({@code stale = true});</li>
 *   <li>il prezzo manuale, se presente ({@code stale = true}).</li>
 * </ol>
 * Il caching delle quotazioni sta nei singoli provider (un {@code @Cacheable} su un metodo chiamato da questa
 * stessa classe non passerebbe dal proxy).
 */
@Service
public class MarketPriceService {

    private static final Logger logger = LoggerFactory.getLogger(MarketPriceService.class);
    // Yahoo non documenta il rate limit: poche richieste in parallelo bastano per un portafoglio personale
    private static final int MAX_CONCURRENT_FETCHES = 4;

    /** Prezzo risolto di un asset, nella valuta {@code currency}. */
    public record ResolvedPrice(BigDecimal price, String currency, LocalDateTime asOf, PriceSource source, boolean stale) {
    }

    private final YahooFinanceService yahoo;
    private final TwelveDataService twelveData;
    private final OpenFigiService openFigi;
    private final InvestmentAssetRepository assetRepository;

    public MarketPriceService(YahooFinanceService yahoo, TwelveDataService twelveData, OpenFigiService openFigi,
                              InvestmentAssetRepository assetRepository) {
        this.yahoo = yahoo;
        this.twelveData = twelveData;
        this.openFigi = openFigi;
        this.assetRepository = assetRepository;
    }

    /**
     * Prezzi di più asset. Le chiamate ai provider avvengono in parallelo (limitato), la scrittura dell'ultimo
     * prezzo noto sul thread chiamante. Gli asset senza alcun prezzo non compaiono nella mappa.
     */
    public Map<UUID, ResolvedPrice> resolveAll(List<InvestmentAsset> assets) {
        Map<UUID, Optional<MarketQuote>> live = fetchLiveQuotes(assets);
        Map<UUID, ResolvedPrice> resolved = new LinkedHashMap<>();
        for (InvestmentAsset asset : assets) {
            Optional<MarketQuote> quote = live.getOrDefault(asset.getId(), Optional.empty());
            Optional<ResolvedPrice> price = assemble(asset, quote);
            price.ifPresent(p -> resolved.put(asset.getId(), p));
            quote.ifPresent(q -> persistLastPrice(asset, q));
        }
        return resolved;
    }

    public Optional<ResolvedPrice> resolve(InvestmentAsset asset) {
        return Optional.ofNullable(resolveAll(List.of(asset)).get(asset.getId()));
    }

    /** Ricerca per ticker, nome o ISIN. Per un ISIN senza risultati su Yahoo si prova OpenFIGI. */
    public List<InstrumentSearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String trimmed = query.trim();
        List<InstrumentSearchResult> results = new ArrayList<>(yahoo.search(trimmed));
        if (results.isEmpty() && OpenFigiService.isIsin(trimmed)) {
            results.addAll(openFigi.searchByIsin(trimmed));
        }
        return results;
    }

    /** Quotazione live di un simbolo dal primo provider che risponde (per ricavare la valuta di un nuovo asset). */
    public Optional<MarketQuote> getLiveQuote(String symbol, PriceSource preferred) {
        for (MarketPriceProvider provider : providersFor(preferred)) {
            Optional<MarketQuote> quote = provider.getQuote(symbol);
            if (quote.isPresent()) {
                return quote;
            }
        }
        return Optional.empty();
    }

    private Map<UUID, Optional<MarketQuote>> fetchLiveQuotes(List<InvestmentAsset> assets) {
        List<InvestmentAsset> toFetch = assets.stream()
                .filter(a -> a.getPriceSource() != PriceSource.MANUAL && a.getSymbol() != null && !a.getSymbol().isBlank())
                .toList();
        Map<UUID, Optional<MarketQuote>> result = new HashMap<>();
        if (toFetch.isEmpty()) {
            return result;
        }
        Semaphore permits = new Semaphore(MAX_CONCURRENT_FETCHES);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<UUID, Future<Optional<MarketQuote>>> futures = new LinkedHashMap<>();
            for (InvestmentAsset asset : toFetch) {
                futures.put(asset.getId(), executor.submit(() -> {
                    permits.acquire();
                    try {
                        return getLiveQuote(asset.getSymbol(), asset.getPriceSource());
                    } finally {
                        permits.release();
                    }
                }));
            }
            futures.forEach((id, future) -> {
                try {
                    result.put(id, future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.put(id, Optional.empty());
                } catch (Exception e) {
                    logger.warn("Recupero prezzo asset {} fallito: {}", id, e.getMessage());
                    result.put(id, Optional.empty());
                }
            });
        }
        return result;
    }

    private List<MarketPriceProvider> providersFor(PriceSource preferred) {
        List<MarketPriceProvider> ordered = preferred == PriceSource.TWELVE_DATA
                ? List.of(twelveData, yahoo)
                : List.of(yahoo, twelveData);
        return ordered.stream().filter(MarketPriceProvider::isConfigured).toList();
    }

    private Optional<ResolvedPrice> assemble(InvestmentAsset asset, Optional<MarketQuote> live) {
        if (asset.getPriceSource() == PriceSource.MANUAL) {
            return manualPrice(asset, false);
        }
        if (live.isPresent()) {
            MarketQuote q = live.get();
            return Optional.of(new ResolvedPrice(q.getPrice(), q.getCurrency(), q.getAsOf(), q.getSource(), false));
        }
        if (asset.getLastPrice() != null && asset.getLastPrice().signum() > 0 && asset.getLastPriceCurrency() != null) {
            return Optional.of(new ResolvedPrice(asset.getLastPrice(), asset.getLastPriceCurrency(),
                    asset.getLastPriceAt(), asset.getPriceSource(), true));
        }
        return manualPrice(asset, true);
    }

    private Optional<ResolvedPrice> manualPrice(InvestmentAsset asset, boolean stale) {
        if (asset.getManualPrice() == null || asset.getManualPrice().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedPrice(asset.getManualPrice(), asset.getCurrency(),
                asset.getManualPriceAt(), PriceSource.MANUAL, stale));
    }

    private void persistLastPrice(InvestmentAsset asset, MarketQuote quote) {
        // Quotazione già salvata (risposta dalla cache del provider): niente UPDATE a ogni lettura del portafoglio
        if (quote.getAsOf() != null && quote.getAsOf().equals(asset.getLastPriceAt())
                && quote.getPrice().compareTo(asset.getLastPrice() != null ? asset.getLastPrice() : BigDecimal.ZERO) == 0) {
            return;
        }
        try {
            assetRepository.updateLastPrice(asset.getId(), quote.getPrice(), quote.getCurrency(), quote.getAsOf());
        } catch (Exception e) {
            // L'ultimo prezzo è solo un fallback: non deve far fallire la lettura del portafoglio
            logger.warn("Salvataggio ultimo prezzo dell'asset {} fallito: {}", asset.getId(), e.getMessage());
        }
    }
}
