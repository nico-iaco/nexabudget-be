package it.iacovelli.nexabudgetbe.service;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BinanceService {

    private static final Logger logger = LoggerFactory.getLogger(BinanceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Client per dati pubblici (prezzi)
    private final SpotClient spotClient = new SpotClientImpl();

    // Client per dati privati (wallet) - da creare on-demand
    private SpotClient createPrivateSpotClient(String apiKey, String apiSecret) {
        return new SpotClientImpl(apiKey, apiSecret);
    }

    @Cacheable(value = CacheConfig.CRYPTO_PRICES_CACHE, key = "#symbol")
    public Optional<BigDecimal> getTickerPrice(String symbol) {
        logger.info("Recupero prezzo non in cache per {}", symbol);
        try {
            // Assumiamo USDT come valuta di quotazione
            String tradingPair = symbol.toUpperCase() + "USDT";
            String jsonResponse = spotClient.createMarket().ticker(Map.of("symbol", tradingPair));

            // Parse JSON: { "symbol": "BTCUSDT", "price": "65000.00" }
            JsonNode root = objectMapper.readTree(jsonResponse);
            String priceStr = root.get("price").asText();
            BigDecimal price = new BigDecimal(priceStr);

            logger.debug("Prezzo {} recuperato: {}", symbol, price);
            return Optional.of(price);
        } catch (Exception e) {
            logger.error("Errore nel recupero prezzo Binance per {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    public List<CryptoBalance> getAccountBalances(String apiKey, String apiSecret) {
        logger.info("Recupero bilancio da Binance...");
        try {
            SpotClient privateClient = createPrivateSpotClient(apiKey, apiSecret);
            // Usa l'endpoint account invece di accountSnapshot per i balances
            Map<String, Object> parameters = Map.of("recvWindow", 5000);
            String jsonResponse = privateClient.createTrade().account(parameters);

            // Parse JSON: { "balances": [ { "asset": "BTC", "free": "0.1", "locked": "0.0" }, ... ] }
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode balancesNode = root.get("balances");

            List<CryptoBalance> balances = new ArrayList<>();
            if (balancesNode != null && balancesNode.isArray()) {
                for (JsonNode balance : balancesNode) {
                    String asset = balance.get("asset").asText();
                    BigDecimal free = new BigDecimal(balance.get("free").asText());
                    BigDecimal locked = new BigDecimal(balance.get("locked").asText());
                    BigDecimal total = free.add(locked);

                    // Filtra solo asset con bilancio positivo
                    if (total.compareTo(BigDecimal.ZERO) > 0) {
                        balances.add(CryptoBalance.builder()
                                .symbol(asset)
                                .amount(total)
                                .build());
                    }
                }
            }

            logger.info("Recuperati {} asset da Binance", balances.size());
            return balances;
        } catch (Exception e) {
            logger.error("Errore nel recupero bilancio Binance: {}", e.getMessage(), e);
            throw new RuntimeException("Impossibile recuperare bilancio Binance", e);
        }
    }
}
