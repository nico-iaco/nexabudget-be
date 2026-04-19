package it.iacovelli.nexabudgetbe.config;

import it.iacovelli.nexabudgetbe.service.ExchangeRateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class CacheWarmupRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CacheWarmupRunner.class);
    private final ExchangeRateService exchangeRateService;

    public CacheWarmupRunner(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("Avvio cache warming per tassi di cambio...");
                exchangeRateService.getRate("USD", "EUR");
                exchangeRateService.getRate("USD", "GBP");
                logger.info("Cache warming completato");
            } catch (Exception e) {
                logger.warn("Cache warming fallito (non critico): {}", e.getMessage());
            }
        });
    }
}
