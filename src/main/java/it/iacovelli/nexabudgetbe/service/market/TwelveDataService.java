package it.iacovelli.nexabudgetbe.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.model.PriceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Fallback opzionale: Twelve Data (free tier 800 chiamate/giorno, 8/minuto). Il piano gratuito copre di fatto solo
 * azioni ed ETF USA, quindi si interrogano solo simboli senza suffisso di borsa. Disattivato senza
 * {@code TWELVEDATA_API_KEY}: l'applicazione parte comunque.
 */
@Service
public class TwelveDataService implements MarketPriceProvider {

    private static final Logger logger = LoggerFactory.getLogger(TwelveDataService.class);
    private static final String BASE_URL = "https://api.twelvedata.com";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;

    @Autowired
    public TwelveDataService(@Value("${nexabudget.market.twelvedata.api-key:}") String apiKey) {
        this(buildRestClient(), apiKey);
    }

    // Per i test
    TwelveDataService(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    @Override
    public PriceSource source() {
        return PriceSource.TWELVE_DATA;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    @Cacheable(value = CacheConfig.MARKET_PRICES_CACHE, key = "'TWELVE:' + #symbol.toUpperCase()", unless = "#result == null")
    public Optional<MarketQuote> getQuote(String symbol) {
        // Simboli con suffisso di borsa (VWCE.DE) non sono coperti dal free tier: non si spende una chiamata
        if (!isConfigured() || !YahooFinanceService.isValidSymbol(symbol) || symbol.contains(".")) {
            return Optional.empty();
        }
        try {
            // Yahoo scrive le classi di azioni con il trattino (BRK-B), Twelve Data con il punto
            String twelveSymbol = symbol.toUpperCase().replace('-', '.');
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/quote")
                            .queryParam("symbol", twelveSymbol)
                            .queryParam("apikey", apiKey)
                            .build())
                    .retrieve()
                    .body(String.class);
            return parseQuote(symbol, body);
        } catch (RestClientException e) {
            // Il messaggio dell'eccezione può contenere l'URL con la chiave: si logga solo il tipo
            logger.warn("Quotazione Twelve Data di {} non disponibile ({})", symbol, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    Optional<MarketQuote> parseQuote(String symbol, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if ("error".equalsIgnoreCase(root.path("status").asText())) {
                logger.warn("Twelve Data: errore per {} ({})", symbol, root.path("message").asText(""));
                return Optional.empty();
            }
            String close = root.path("close").asText("");
            String currency = root.path("currency").asText("");
            if (close.isBlank() || currency.isBlank()) {
                return Optional.empty();
            }
            BigDecimal price = new BigDecimal(close);
            if (price.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(MarketQuote.builder()
                    .symbol(symbol.toUpperCase())
                    .price(price)
                    .currency(currency.toUpperCase())
                    .asOf(LocalDateTime.now())
                    .source(PriceSource.TWELVE_DATA)
                    .build());
        } catch (Exception e) {
            logger.error("Errore parsing risposta Twelve Data per {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }
}
