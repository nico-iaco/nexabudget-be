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
import java.util.Optional;

/**
 * Responsabile del recupero (e caching) dei tassi di cambio.
 * Fornisce rate generic source->target.
 */
@Service
public class ExchangeRateService {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateService.class);

    private static final String EXCHANGE_RATE_API_URL = "https://api.exchangerate-api.com/v4/latest/";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ExchangeRateService() {
        this.restClient = RestClient.builder()
                .baseUrl(EXCHANGE_RATE_API_URL)
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(5));
                    setReadTimeout(Duration.ofSeconds(5));
                }})
                .build();
        this.objectMapper = new ObjectMapper();
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

