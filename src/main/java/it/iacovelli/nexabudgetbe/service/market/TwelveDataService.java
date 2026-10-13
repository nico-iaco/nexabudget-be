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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback opzionale: Twelve Data (free tier 800 chiamate/giorno, 8/minuto). Disattivato senza
 * {@code TWELVEDATA_API_KEY}: l'applicazione parte comunque.
 * <p>
 * I simboli arrivano nella notazione Yahoo: senza suffisso (AAPL, BRK-B) sono titoli USA, con suffisso (VWCE.MI)
 * il suffisso diventa il {@code mic_code} di Twelve Data ({@code symbol=VWCE&mic_code=XMIL}). Quali borse siano
 * incluse dipende dal piano della chiave: se Twelve Data risponde che il simbolo non è disponibile nel piano
 * (o è sconosciuto), l'errore viene loggato con il suo messaggio e il simbolo non viene più interrogato per un
 * giorno, per non consumare i crediti giornalieri a vuoto.
 */
@Service
public class TwelveDataService implements MarketPriceProvider {

    private static final Logger logger = LoggerFactory.getLogger(TwelveDataService.class);
    private static final String BASE_URL = "https://api.twelvedata.com";
    private static final Duration DEFAULT_UNSUPPORTED_TTL = Duration.ofDays(1);
    private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofMinutes(1);

    // Suffisso Yahoo -> MIC della borsa
    private static final Map<String, String> MIC_BY_YAHOO_SUFFIX = Map.ofEntries(
            Map.entry("MI", "XMIL"), Map.entry("DE", "XETR"), Map.entry("F", "XFRA"), Map.entry("AS", "XAMS"),
            Map.entry("PA", "XPAR"), Map.entry("L", "XLON"), Map.entry("SW", "XSWX"), Map.entry("MC", "XMAD"),
            Map.entry("VI", "XWBO"), Map.entry("BR", "XBRU"), Map.entry("LS", "XLIS"), Map.entry("IR", "XDUB"),
            Map.entry("HE", "XHEL"), Map.entry("ST", "XSTO"), Map.entry("CO", "XCSE"), Map.entry("OL", "XOSL"));

    /** Simbolo e borsa nella notazione Twelve Data (micCode null = titolo USA). */
    record Target(String symbol, String micCode) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final Duration unsupportedTtl;
    private final Duration rateLimitCooldown;
    // Simboli che Twelve Data non serve con questa chiave (piano insufficiente o simbolo sconosciuto), per pod
    private final Map<String, Instant> unsupportedUntil = new ConcurrentHashMap<>();
    private volatile Instant rateLimitedUntil = Instant.EPOCH;

    @Autowired
    public TwelveDataService(@Value("${nexabudget.market.twelvedata.api-key:}") String apiKey) {
        this(buildRestClient(), apiKey, DEFAULT_UNSUPPORTED_TTL, DEFAULT_RATE_LIMIT_COOLDOWN);
    }

    // Per i test
    TwelveDataService(RestClient restClient, String apiKey) {
        this(restClient, apiKey, DEFAULT_UNSUPPORTED_TTL, DEFAULT_RATE_LIMIT_COOLDOWN);
    }

    TwelveDataService(RestClient restClient, String apiKey, Duration unsupportedTtl, Duration rateLimitCooldown) {
        this.restClient = restClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.unsupportedTtl = unsupportedTtl;
        this.rateLimitCooldown = rateLimitCooldown;
        if (this.apiKey.isEmpty()) {
            logger.info("Twelve Data non configurato (TWELVEDATA_API_KEY assente): provider disattivato");
        } else {
            logger.info("Twelve Data configurato: fallback attivo per i prezzi degli investimenti");
        }
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

    /** Converte il simbolo Yahoo in simbolo + borsa Twelve Data; empty se il suffisso non è mappato. */
    static Optional<Target> toTarget(String yahooSymbol) {
        String s = yahooSymbol.toUpperCase();
        int dot = s.lastIndexOf('.');
        if (dot < 0) {
            // Yahoo scrive le classi di azioni USA con il trattino (BRK-B), Twelve Data con il punto
            return Optional.of(new Target(s.replace('-', '.'), null));
        }
        String mic = MIC_BY_YAHOO_SUFFIX.get(s.substring(dot + 1));
        return mic == null ? Optional.empty() : Optional.of(new Target(s.substring(0, dot), mic));
    }

    @Override
    @Cacheable(value = CacheConfig.MARKET_PRICES_CACHE, key = "'TWELVE:' + #symbol.toUpperCase()", unless = "#result == null")
    public Optional<MarketQuote> getQuote(String symbol) {
        if (!isConfigured() || !YahooFinanceService.isValidSymbol(symbol)) {
            return Optional.empty();
        }
        Optional<Target> target = toTarget(symbol);
        if (target.isEmpty()) {
            logger.debug("Twelve Data: suffisso di borsa di {} non mappato, simbolo saltato", symbol);
            return Optional.empty();
        }
        String key = symbol.toUpperCase();
        Instant now = Instant.now();
        if (now.isBefore(rateLimitedUntil)) {
            return Optional.empty();
        }
        Instant blocked = unsupportedUntil.get(key);
        if (blocked != null) {
            if (now.isBefore(blocked)) {
                return Optional.empty();
            }
            unsupportedUntil.remove(key);
        }
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/quote").queryParam("symbol", target.get().symbol());
                        if (target.get().micCode() != null) {
                            uriBuilder.queryParam("mic_code", target.get().micCode());
                        }
                        return uriBuilder.queryParam("apikey", apiKey).build();
                    })
                    .retrieve()
                    .body(String.class);
            return parseQuote(symbol, body);
        } catch (HttpClientErrorException e) {
            // Twelve Data può rispondere con un 4xx e un JSON {code, message}: se ne ricava il motivo
            return onApiError(key, e.getStatusCode().value(), errorMessage(e.getResponseBodyAsString()));
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
                return onApiError(symbol.toUpperCase(), root.path("code").asInt(0), root.path("message").asText(""));
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
            // Come per Yahoo: i listing in sottounità (pence) si riportano alla valuta intera
            YahooFinanceService.Normalized normalized = YahooFinanceService.normalizeMinorUnit(price, currency);
            return Optional.of(MarketQuote.builder()
                    .symbol(symbol.toUpperCase())
                    .price(normalized.price())
                    .currency(normalized.currency())
                    .asOf(quoteTime(root))
                    .source(PriceSource.TWELVE_DATA)
                    .build());
        } catch (Exception e) {
            logger.error("Errore parsing risposta Twelve Data per {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    // Ora della quotazione (non del download): con la cache di un giorno la differenza conta
    private LocalDateTime quoteTime(JsonNode root) {
        long seconds = root.path("last_quote_at").asLong(root.path("timestamp").asLong(0));
        return seconds > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.systemDefault())
                : LocalDateTime.now();
    }

    private String errorMessage(String body) {
        try {
            return objectMapper.readTree(body).path("message").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private Optional<MarketQuote> onApiError(String symbol, int code, String message) {
        String lower = message == null ? "" : message.toLowerCase();
        if (code == 429 || lower.contains("run out of api credits") || lower.contains("api credits")) {
            if (!Instant.now().isBefore(rateLimitedUntil)) {
                logger.warn("Twelve Data: limite di richieste/crediti raggiunto, sospendo le chiamate per {} ({})",
                        rateLimitCooldown, message);
            }
            rateLimitedUntil = Instant.now().plus(rateLimitCooldown);
        } else if (code == 401) {
            logger.warn("Twelve Data: chiave API non valida o non accettata ({})", message);
        } else if (code == 403 || code == 400 || code == 404 || lower.contains("plan")) {
            // Piano insufficiente o simbolo/borsa sconosciuti: ritentare ogni 15 minuti brucerebbe i crediti giornalieri
            if (unsupportedUntil.put(symbol, Instant.now().plus(unsupportedTtl)) == null) {
                logger.warn("Twelve Data non fornisce {} con questa chiave (codice {}): {}. Non lo riprovo per {}",
                        symbol, code, message, unsupportedTtl);
            }
        } else {
            logger.warn("Twelve Data: errore per {} (codice {}): {}", symbol, code, message);
        }
        return Optional.empty();
    }
}
