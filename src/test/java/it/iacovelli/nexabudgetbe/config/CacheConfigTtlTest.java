package it.iacovelli.nexabudgetbe.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CacheConfigTtlTest {

    private Map<String, RedisCacheConfiguration> configs(Duration priceTtl) {
        RedisCacheManager manager = (RedisCacheManager) new CacheConfig()
                .cacheManager(mock(RedisConnectionFactory.class), new ObjectMapper(), priceTtl);
        // Le configurazioni per cache si popolano all'inizializzazione (non apre connessioni a Redis)
        manager.afterPropertiesSet();
        return manager.getCacheConfigurations();
    }

    private static Duration ttl(Map<String, RedisCacheConfiguration> configs, String cache) {
        return configs.get(cache).getTtlFunction().getTimeToLive("key", "value");
    }

    @Test
    void marketPrices_useTheConfiguredTtl_whilePortfolioStaysShort() {
        Map<String, RedisCacheConfiguration> configs = configs(Duration.ofDays(1));

        // Le quotazioni vivono un giorno (Yahoo rate-limita), il portafoglio calcolato solo 15 minuti
        assertEquals(Duration.ofDays(1), ttl(configs, CacheConfig.MARKET_PRICES_CACHE));
        assertEquals(Duration.ofMinutes(15), ttl(configs, CacheConfig.INVESTMENT_PORTFOLIO_CACHE));
    }

    @Test
    void marketPriceTtl_isConfigurable() {
        assertEquals(Duration.ofHours(6), ttl(configs(Duration.ofHours(6)), CacheConfig.MARKET_PRICES_CACHE));
    }

    @Test
    void propertyValueFormats_convertToDuration() {
        // @Value("${nexabudget.market.price-cache-ttl:1d}") usa questo servizio di conversione: il default "1d" e i
        // formati documentati devono diventare Duration (un valore non valido farebbe fallire l'avvio)
        ApplicationConversionService conversion = new ApplicationConversionService();

        assertEquals(Duration.ofDays(1), conversion.convert("1d", Duration.class));
        assertEquals(Duration.ofHours(12), conversion.convert("12h", Duration.class));
        assertEquals(Duration.ofMinutes(30), conversion.convert("PT30M", Duration.class));
    }

    @Test
    void otherCaches_areUnaffected() {
        Map<String, RedisCacheConfiguration> configs = configs(Duration.ofDays(1));

        assertEquals(CacheConfig.CRYPTO_CACHE_TTL, ttl(configs, CacheConfig.CRYPTO_PRICES_CACHE));
        assertEquals(CacheConfig.CRYPTO_CACHE_TTL, ttl(configs, CacheConfig.PORTFOLIO_CACHE));
        assertEquals(CacheConfig.AI_REPORT_RESULTS_TTL, ttl(configs, CacheConfig.AI_REPORTS_RESULTS_CACHE));
    }
}
