package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * BinanceService: i fallimenti di Simple Earn devono propagarsi (niente sync parziale che cancella holdings)
 * e la mappa prezzi vuota restituita in caso di errore non va cachata.
 */
class BinanceServiceTest {

    private static final String FLEXIBLE = "https://api.binance.com/sapi/v1/simple-earn/flexible/position";
    private static final String LOCKED = "https://api.binance.com/sapi/v1/simple-earn/locked/position";

    private MockRestServiceServer server;
    private BinanceService binanceService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.binance.com");
        server = MockRestServiceServer.bindTo(builder).build();
        binanceService = spy(new BinanceService(builder.build()));
        doReturn(List.of(new CryptoBalance("BTC", BigDecimal.ONE)))
                .when(binanceService).getAccountBalances(anyString(), anyString());
    }

    @Test
    void earnFailurePropagates() {
        server.expect(once(), requestTo(startsWith(FLEXIBLE))).andRespond(withServerError());

        assertThrows(RuntimeException.class, () -> binanceService.getAllWalletsIncludingEarn("key", "secret"));
        server.verify();
    }

    @Test
    void lockedEarnFailurePropagates() {
        server.expect(once(), requestTo(startsWith(FLEXIBLE)))
                .andRespond(withSuccess("{\"rows\": []}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(LOCKED))).andRespond(withServerError());

        assertThrows(RuntimeException.class, () -> binanceService.getAllWalletsIncludingEarn("key", "secret"));
        server.verify();
    }

    @Test
    void spotAndEarnAreCombined() {
        server.expect(once(), requestTo(startsWith(FLEXIBLE)))
                .andRespond(withSuccess("{\"rows\": [{\"asset\": \"BTC\", \"totalAmount\": \"0.5\"}]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(LOCKED)))
                .andRespond(withSuccess("{\"rows\": [{\"asset\": \"ETH\", \"amount\": \"2\"}]}", MediaType.APPLICATION_JSON));

        Map<String, BigDecimal> result = binanceService.getAllWalletsIncludingEarn("key", "secret").stream()
                .collect(Collectors.toMap(CryptoBalance::getSymbol, CryptoBalance::getAmount));

        assertEquals(0, new BigDecimal("1.5").compareTo(result.get("BTC")));
        assertEquals(0, new BigDecimal("2").compareTo(result.get("ETH")));
        server.verify();
    }

    @Test
    void emptyBatchPricesAreNotCached() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.binance.com");
        MockRestServiceServer pricesServer = MockRestServiceServer.bindTo(builder).build();
        pricesServer.expect(once(), requestTo(endsWith("/api/v3/ticker/price"))).andRespond(withServerError());
        pricesServer.expect(once(), requestTo(endsWith("/api/v3/ticker/price")))
                .andRespond(withSuccess("[{\"symbol\": \"BTCUSDT\", \"price\": \"60000\"}]", MediaType.APPLICATION_JSON));

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(RestClient.class, builder::build);
            ctx.register(CachingConfig.class);
            ctx.refresh();
            BinanceService cached = ctx.getBean(BinanceService.class);

            assertTrue(cached.getAllTickerPricesUsdt().isEmpty());     // errore: mappa vuota, non cachata
            Map<String, BigDecimal> prices = cached.getAllTickerPricesUsdt(); // nuova chiamata
            assertEquals(0, new BigDecimal("60000").compareTo(prices.get("BTC")));
            assertEquals(prices, cached.getAllTickerPricesUsdt());     // ora dalla cache (nessuna terza richiesta)
        }
        pricesServer.verify();
    }

    @Configuration
    @EnableCaching
    static class CachingConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheConfig.CRYPTO_PRICES_CACHE);
        }

        @Bean
        BinanceService binanceService(RestClient restClient) {
            return new BinanceService(restClient);
        }
    }
}
