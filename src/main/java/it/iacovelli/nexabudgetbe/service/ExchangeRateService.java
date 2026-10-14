package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Responsabile del recupero (e caching) dei tassi di cambio.
 * Fornisce rate generic source->target.
 */
@Service
public class ExchangeRateService {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateService.class);

    private static final String EXCHANGE_RATE_API_URL = "https://api.exchangerate-api.com/v4/latest/";

    // Tassi storici BCE (gratuito, senza chiave): per un giorno non lavorativo restituisce l'ultimo giorno utile precedente
    private static final String HISTORICAL_RATE_API_URL = "https://api.frankfurter.dev/v1/";

    private final RestClient restClient;
    private final RestClient historicalRestClient;
    private final ObjectMapper objectMapper;

    public ExchangeRateService() {
        this.restClient = RestClient.builder()
                .baseUrl(EXCHANGE_RATE_API_URL)
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(5));
                    setReadTimeout(Duration.ofSeconds(5));
                }})
                .build();
        this.historicalRestClient = RestClient.builder()
                .baseUrl(HISTORICAL_RATE_API_URL)
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(5));
                    setReadTimeout(Duration.ofSeconds(5));
                }})
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Tasso source->target del giorno indicato (null se non disponibile). Un tasso storico non cambia, quindi sta in
     * cache a lungo; il risultato nullo non viene cachato. Restituisce un BigDecimal e non un Optional per poter
     * usare {@code unless = "#result == null"} anche nella build native.
     */
    @Cacheable(value = CacheConfig.HISTORICAL_EXCHANGE_RATES_CACHE,
               key = "#sourceCurrency + '|' + #targetCurrency + '|' + #date", unless = "#result == null")
    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public BigDecimal getHistoricalRate(String sourceCurrency, String targetCurrency, LocalDate date) {
        String jsonResponse = historicalRestClient.get()
                .uri(uriBuilder -> uriBuilder.path(date.toString())
                        .queryParam("base", sourceCurrency.toUpperCase())
                        .queryParam("symbols", targetCurrency.toUpperCase())
                        .build())
                .retrieve()
                .body(String.class);
        try {
            JsonNode rate = objectMapper.readTree(jsonResponse).path("rates").path(targetCurrency.toUpperCase());
            if (rate.isMissingNode() || rate.isNull()) {
                logger.warn("Tasso storico {}->{} del {} non trovato", sourceCurrency, targetCurrency, date);
                return null;
            }
            return new BigDecimal(rate.asText());
        } catch (Exception e) {
            logger.error("Errore parsing tasso storico {}->{} del {}: {}", sourceCurrency, targetCurrency, date, e.getMessage());
            return null;
        }
    }

    @Recover
    public BigDecimal recoverGetHistoricalRate(RestClientException e, String sourceCurrency, String targetCurrency, LocalDate date) {
        logger.error("Tasso storico {}->{} del {} non disponibile dopo i retry: {}", sourceCurrency, targetCurrency, date, e.getMessage());
        return null;
    }

    /**
     * Recupera e cache il tasso source->target.
     * Caching per chiave = targetCurrency + '|' + sourceCurrency così da distinguere basi diverse.
     * I risultati vuoti (Optional.empty) non vengono cachati per permettere retry successivi.
     */
    @Cacheable(value = CacheConfig.EXCHANGE_RATES_CACHE, key = "#sourceCurrency + '|' + #targetCurrency",
               unless = "#result == null")
    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public Optional<BigDecimal> getRate(String sourceCurrency, String targetCurrency) {
        logger.info("Tasso non in cache: {} -> {}", sourceCurrency, targetCurrency);
        String jsonResponse = restClient.get()
                .uri(sourceCurrency.toUpperCase())
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode ratesNode = root.get("rates");
            if (ratesNode != null && ratesNode.has(targetCurrency.toUpperCase())) {
                BigDecimal rate = new BigDecimal(ratesNode.get(targetCurrency.toUpperCase()).asText());
                logger.debug("Rate {}->{} = {}", sourceCurrency, targetCurrency, rate);
                return Optional.of(rate);
            }
            logger.warn("Valuta target {} non trovata per base {}", targetCurrency, sourceCurrency);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Errore parsing tasso {}->{}: {}", sourceCurrency, targetCurrency, e.getMessage());
            return Optional.empty();
        }
    }

    @Recover
    public Optional<BigDecimal> recoverGetRate(RestClientException e, String sourceCurrency, String targetCurrency) {
        logger.error("Tasso {}->{} non disponibile dopo i retry: {}", sourceCurrency, targetCurrency, e.getMessage());
        return Optional.empty();
    }
}

