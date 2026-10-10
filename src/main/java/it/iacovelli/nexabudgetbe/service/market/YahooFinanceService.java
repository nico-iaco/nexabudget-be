package it.iacovelli.nexabudgetbe.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import it.iacovelli.nexabudgetbe.model.PriceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Quotazioni e ricerca strumenti da Yahoo Finance (endpoint pubblici non ufficiali, senza chiave).
 * <p>
 * Usa solo {@code /v8/finance/chart} e {@code /v1/finance/search}, che rispondono senza cookie/crumb: l'invio di
 * cookie fa scattare il rate limit molto prima. Gli endpoint non sono documentati e possono cambiare o rispondere
 * 429: ogni errore si traduce in {@code empty} e il chiamante passa al provider successivo.
 */
@Service
public class YahooFinanceService implements MarketPriceProvider {

    private static final Logger logger = LoggerFactory.getLogger(YahooFinanceService.class);

    private static final String YAHOO_BASE_URL = "https://query1.finance.yahoo.com";
    // Yahoo risponde 429 a molti User-Agent non da browser
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    // Il simbolo finisce in un path: vietato qualunque carattere (es. '/') che possa alterarlo
    private static final Pattern VALID_SYMBOL = Pattern.compile("[A-Za-z0-9.^=_\\-]{1,32}");

    // Yahoo blocca l'IP per qualche minuto dopo un burst di richieste (osservato: ~30 chiamate in pochi minuti, poi 429
    // su ogni richiesta). Continuare a chiamare durante il blocco lo prolunga: dopo un 429 si smette per questo periodo
    private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofMinutes(2);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final Duration rateLimitCooldown;
    // Per pod: ogni istanza scopre da sola il blocco
    private volatile Instant blockedUntil = Instant.EPOCH;

    @Autowired
    public YahooFinanceService(@Value("${nexabudget.market.yahoo.enabled:true}") boolean enabled) {
        this(buildRestClient(), enabled, DEFAULT_RATE_LIMIT_COOLDOWN);
    }

    // Per i test: permette di iniettare un RestClient legato a MockRestServiceServer
    YahooFinanceService(RestClient restClient, boolean enabled) {
        this(restClient, enabled, DEFAULT_RATE_LIMIT_COOLDOWN);
    }

    YahooFinanceService(RestClient restClient, boolean enabled, Duration rateLimitCooldown) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.rateLimitCooldown = rateLimitCooldown;
    }

    private boolean isRateLimited() {
        return Instant.now().isBefore(blockedUntil);
    }

    private void onRateLimited(String what) {
        if (!isRateLimited()) {
            logger.warn("Yahoo ha risposto 429 ({}): sospendo le chiamate per {}", what, rateLimitCooldown);
        }
        blockedUntil = Instant.now().plus(rateLimitCooldown);
    }

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(YAHOO_BASE_URL)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    @Override
    public PriceSource source() {
        return PriceSource.YAHOO;
    }

    @Override
    public boolean isConfigured() {
        return enabled;
    }

    public static boolean isValidSymbol(String symbol) {
        return symbol != null && VALID_SYMBOL.matcher(symbol).matches();
    }

    // unless: un risultato vuoto (errore/rate limit) non va cachato, bloccherebbe i prezzi per tutto il TTL
    @Override
    @Cacheable(value = CacheConfig.MARKET_PRICES_CACHE, key = "'YAHOO:' + #symbol.toUpperCase()", unless = "#result == null")
    // Si ritentano solo errori di rete e 5xx; i 4xx sono gestiti nel metodo (vedi sotto)
    @Retryable(retryFor = RestClientException.class, maxAttempts = 2, backoff = @Backoff(delay = 500))
    public Optional<MarketQuote> getQuote(String symbol) {
        if (!enabled || !isValidSymbol(symbol) || isRateLimited()) {
            return Optional.empty();
        }
        String normalized = symbol.toUpperCase();
        String body;
        try {
            body = restClient.get()
                    .uri("/v8/finance/chart/{symbol}?range=1d&interval=1d", normalized)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            // 429 (rate limit) e 404 (simbolo sconosciuto): ritentare subito peggiora il rate limit, si passa al provider successivo
            if (e.getStatusCode().value() == 429) {
                onRateLimited(normalized);
            } else {
                logger.warn("Yahoo ha risposto {} per {}", e.getStatusCode().value(), normalized);
            }
            return Optional.empty();
        }
        return parseChart(normalized, body);
    }

    @Recover
    public Optional<MarketQuote> recoverGetQuote(RestClientException e, String symbol) {
        logger.warn("Quotazione Yahoo di {} non disponibile: {}", symbol, e.getMessage());
        return Optional.empty();
    }

    Optional<MarketQuote> parseChart(String symbol, String body) {
        try {
            JsonNode chart = objectMapper.readTree(body).path("chart");
            JsonNode result = chart.path("result");
            if (!result.isArray() || result.isEmpty()) {
                logger.warn("Yahoo: nessun risultato per {} ({})", symbol, chart.path("error").path("description").asText(""));
                return Optional.empty();
            }
            JsonNode meta = result.get(0).path("meta");
            BigDecimal price = readPrice(meta, result.get(0));
            String currency = meta.path("currency").asText("");
            if (price == null || price.signum() <= 0 || currency.isBlank()) {
                logger.warn("Yahoo: prezzo o valuta mancanti per {}", symbol);
                return Optional.empty();
            }
            // Alcuni listing sono quotati in sottounità (GBp = pence): senza normalizzare il valore sarebbe 100 volte più grande
            Normalized normalized = normalizeMinorUnit(price, currency);
            long time = meta.path("regularMarketTime").asLong(0);
            LocalDateTime asOf = time > 0
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(time), ZoneId.systemDefault())
                    : LocalDateTime.now();
            return Optional.of(MarketQuote.builder()
                    .symbol(symbol.toUpperCase())
                    .price(normalized.price())
                    .currency(normalized.currency())
                    .asOf(asOf)
                    .source(PriceSource.YAHOO)
                    .build());
        } catch (Exception e) {
            logger.error("Errore parsing risposta Yahoo per {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    private BigDecimal readPrice(JsonNode meta, JsonNode result) {
        JsonNode regular = meta.path("regularMarketPrice");
        if (regular.isNumber()) {
            return regular.decimalValue();
        }
        // Fondi senza prezzo "live": ultimo close valorizzato
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        for (int i = closes.size() - 1; i >= 0; i--) {
            if (closes.get(i).isNumber()) {
                return closes.get(i).decimalValue();
            }
        }
        return null;
    }

    record Normalized(BigDecimal price, String currency) {
    }

    static Normalized normalizeMinorUnit(BigDecimal price, String currency) {
        String major = switch (currency) {
            case "GBp", "GBX" -> "GBP";
            case "ZAc" -> "ZAR";
            case "ILA" -> "ILS";
            default -> null;
        };
        if (major == null) {
            return new Normalized(price, currency.toUpperCase());
        }
        return new Normalized(price.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP), major);
    }

    // unless: la lista vuota (nessun risultato o errore) non va cachata.
    // Si restituisce sempre una ArrayList: nell'immagine nativa SpEL non trova isEmpty() sulle liste immutabili
    // del JDK (ImmutableCollections$ListN non ha reflection) e la chiamata finirebbe in 500
    @Cacheable(value = CacheConfig.MARKET_SEARCH_CACHE, key = "'YAHOO:' + #query.trim().toLowerCase()",
            unless = "#result == null || #result.isEmpty()")
    public List<InstrumentSearchResult> search(String query) {
        if (!enabled || query == null || query.isBlank() || isRateLimited()) {
            return new ArrayList<>();
        }
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/finance/search")
                            .queryParam("q", query.trim())
                            .queryParam("quotesCount", 10)
                            .queryParam("newsCount", 0)
                            .build())
                    .retrieve()
                    .body(String.class);
            return parseSearch(body);
        } catch (HttpClientErrorException.TooManyRequests e) {
            onRateLimited("ricerca");
            return new ArrayList<>();
        } catch (RestClientException e) {
            logger.warn("Ricerca Yahoo '{}' non disponibile: {}", query, e.getMessage());
            return new ArrayList<>();
        }
    }

    List<InstrumentSearchResult> parseSearch(String body) {
        List<InstrumentSearchResult> results = new ArrayList<>();
        try {
            for (JsonNode quote : objectMapper.readTree(body).path("quotes")) {
                String symbol = quote.path("symbol").asText("");
                if (symbol.isBlank() || !isValidSymbol(symbol)) {
                    continue;
                }
                String name = quote.path("longname").asText(quote.path("shortname").asText(symbol));
                results.add(InstrumentSearchResult.builder()
                        .symbol(symbol)
                        .name(name)
                        .exchange(quote.path("exchDisp").asText(quote.path("exchange").asText("")))
                        .suggestedType(mapQuoteType(quote.path("quoteType").asText("")))
                        .provider("YAHOO")
                        .build());
            }
        } catch (Exception e) {
            logger.error("Errore parsing ricerca Yahoo: {}", e.getMessage());
        }
        return results;
    }

    static InvestmentAssetType mapQuoteType(String quoteType) {
        return switch (quoteType.toUpperCase()) {
            case "ETF" -> InvestmentAssetType.ETF;
            case "EQUITY" -> InvestmentAssetType.STOCK;
            case "MUTUALFUND" -> InvestmentAssetType.FUND;
            case "BOND" -> InvestmentAssetType.BOND;
            default -> InvestmentAssetType.OTHER;
        };
    }
}
