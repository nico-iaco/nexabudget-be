package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
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
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Recupera e cache il tasso source->target.
     * Caching per chiave = targetCurrency + '|' + sourceCurrency così da distinguere basi diverse.
     */
    @Cacheable(value = CacheConfig.EXCHANGE_RATES_CACHE, key = "#sourceCurrency + '|' + #targetCurrency")
    public Optional<BigDecimal> getRate(String sourceCurrency, String targetCurrency) {
        logger.info("Tasso non in cache: {} -> {}", sourceCurrency, targetCurrency);
        try {
            String jsonResponse = restClient.get()
                    .uri(sourceCurrency.toUpperCase())
                    .retrieve()
                    .body(String.class);
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
            logger.error("Errore recupero tasso {}->{}: {}", sourceCurrency, targetCurrency, e.getMessage());
            return Optional.empty();
        }
    }
}

