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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class BinanceService {

    private static final Logger logger = LoggerFactory.getLogger(BinanceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String BINANCE_API_BASE = "https://api.binance.com";

    // Client per dati pubblici (prezzi)
    private final SpotClient spotClient = new SpotClientImpl();

    // Client per dati privati (wallet) - da creare on-demand
    private SpotClient createPrivateSpotClient(String apiKey, String apiSecret) {
        return new SpotClientImpl(apiKey, apiSecret);
    }

    // Genera la signature HMAC SHA256 per chiamate SAPI firmate
    private String generateSignature(String queryString, String apiSecret) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(keySpec);
            byte[] hash = sha256Hmac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Errore generazione signature", e);
        }
    }

    @Cacheable(value = CacheConfig.CRYPTO_PRICES_CACHE, key = "#symbol")
    public Optional<BigDecimal> getTickerPrice(String symbol) {
        logger.info("Recupero prezzo non in cache per {}", symbol);

        if ("USDT".equalsIgnoreCase(symbol)) {
            return Optional.of(BigDecimal.ONE);
        } else if ("ETHW".equalsIgnoreCase(symbol)) {
            symbol = "ETH";
        }

        try {
            // Assumiamo USDT come valuta di quotazione
            String tradingPair = symbol.toUpperCase() + "USDT";
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", tradingPair);
            String jsonResponse = spotClient.createMarket().ticker(parameters);

            // Parse JSON: { "symbol": "BTCUSDT", "weightedAvgPrice": "65000.00" }
            JsonNode root = objectMapper.readTree(jsonResponse);
            String priceStr = root.get("weightedAvgPrice").asText();
            BigDecimal price = new BigDecimal(priceStr);

            logger.debug("Prezzo {} recuperato: {}", symbol, price);
            return Optional.of(price);
        } catch (Exception e) {
            logger.error("Errore nel recupero prezzo Binance per {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    public List<CryptoBalance> getAccountBalances(String apiKey, String apiSecret) {
        logger.info("Recupero bilancio da Binance Spot Account...");
        try {
            SpotClient privateClient = createPrivateSpotClient(apiKey, apiSecret);

            // /api/v3/account
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("recvWindow", 60000);
            String jsonResponse = privateClient.createTrade().account(parameters);

            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode balancesNode = root.get("balances");

            List<CryptoBalance> balances = new ArrayList<>();
            if (balancesNode != null && balancesNode.isArray()) {
                for (JsonNode balance : balancesNode) {
                    String asset = balance.get("asset").asText();
                    BigDecimal free = new BigDecimal(balance.get("free").asText());
                    BigDecimal locked = new BigDecimal(balance.get("locked").asText());
                    BigDecimal total = free.add(locked);
                    if (total.compareTo(BigDecimal.ZERO) > 0) {
                        balances.add(CryptoBalance.builder().symbol(asset).amount(total).build());
                    }
                }
            }

            logger.info("Recuperati {} asset da Binance Spot Account", balances.size());
            return balances;
        } catch (Exception e) {
            logger.error("Errore nel recupero bilancio Binance: {}", e.getMessage(), e);
            throw new RuntimeException("Impossibile recuperare bilancio Binance: " + e.getMessage(), e);
        }
    }

    /**
     * Recupera tutti i balance da TUTTI i wallet (Spot, Funding, Earn, etc.) via getUserAsset
     */
    public List<CryptoBalance> getAllWalletBalances(String apiKey, String apiSecret) {
        logger.info("Recupero bilancio da tutti i wallet Binance (incluso Earn)...");
        try {
            SpotClient privateClient = createPrivateSpotClient(apiKey, apiSecret);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("recvWindow", 60000);
            parameters.put("needBtcValuation", true);
            String jsonResponse = privateClient.createWallet().getUserAsset(parameters);

            JsonNode root = objectMapper.readTree(jsonResponse);
            List<CryptoBalance> balances = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode assetNode : root) {
                    String asset = assetNode.get("asset").asText();
                    BigDecimal free = new BigDecimal(assetNode.get("free").asText());
                    BigDecimal locked = new BigDecimal(assetNode.get("locked").asText());
                    BigDecimal freeze = assetNode.has("freeze") ? new BigDecimal(assetNode.get("freeze").asText()) : BigDecimal.ZERO;
                    BigDecimal total = free.add(locked).add(freeze);
                    if (total.compareTo(BigDecimal.ZERO) > 0) {
                        balances.add(CryptoBalance.builder().symbol(asset).amount(total).build());
                    }
                }
            }
            logger.info("Recuperati {} asset da tutti i wallet Binance", balances.size());
            return balances;
        } catch (Exception e) {
            logger.error("Errore nel recupero bilancio da tutti i wallet Binance: {}", e.getMessage(), e);
            logger.info("Fallback: provo a recuperare solo dallo Spot Account");
            return getAccountBalances(apiKey, apiSecret);
        }
    }

    /**
     * Recupera asset da Simple Earn (Flexible Savings) con chiamata HTTP diretta
     * Endpoint: /sapi/v1/simple-earn/flexible/position
     */
    private List<CryptoBalance> getSimpleEarnFlexibleBalances(String apiKey, String apiSecret) {
        logger.info("Recupero asset da Simple Earn Flexible...");
        try {
            long timestamp = System.currentTimeMillis();
            String queryString = "timestamp=" + timestamp + "&recvWindow=60000";
            String signature = generateSignature(queryString, apiSecret);
            String url = BINANCE_API_BASE + "/sapi/v1/simple-earn/flexible/position?" + queryString + "&signature=" + signature;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", apiKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode rowsNode = root.get("rows");

            List<CryptoBalance> balances = new ArrayList<>();
            if (rowsNode != null && rowsNode.isArray()) {
                for (JsonNode row : rowsNode) {
                    if (row.has("asset") && row.has("totalAmount")) {
                        String asset = row.get("asset").asText();
                        BigDecimal amount = new BigDecimal(row.get("totalAmount").asText());
                        if (amount.compareTo(BigDecimal.ZERO) > 0) {
                            balances.add(CryptoBalance.builder().symbol(asset).amount(amount).build());
                        }
                    }
                }
            }
            logger.info("Recuperati {} asset da Simple Earn Flexible", balances.size());
            return balances;
        } catch (Exception e) {
            logger.error("Errore recupero Simple Earn Flexible: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Recupera asset da Simple Earn (Locked Savings) con chiamata HTTP diretta
     * Endpoint: /sapi/v1/simple-earn/locked/position
     */
    private List<CryptoBalance> getSimpleEarnLockedBalances(String apiKey, String apiSecret) {
        logger.info("Recupero asset da Simple Earn Locked...");
        try {
            long timestamp = System.currentTimeMillis();
            String queryString = "timestamp=" + timestamp + "&recvWindow=60000";
            String signature = generateSignature(queryString, apiSecret);
            String url = BINANCE_API_BASE + "/sapi/v1/simple-earn/locked/position?" + queryString + "&signature=" + signature;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", apiKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode rowsNode = root.get("rows");

            List<CryptoBalance> balances = new ArrayList<>();
            if (rowsNode != null && rowsNode.isArray()) {
                for (JsonNode row : rowsNode) {
                    if (row.has("asset") && row.has("amount")) {
                        String asset = row.get("asset").asText();
                        BigDecimal amount = new BigDecimal(row.get("amount").asText());
                        if (amount.compareTo(BigDecimal.ZERO) > 0) {
                            balances.add(CryptoBalance.builder().symbol(asset).amount(amount).build());
                        }
                    }
                }
            }
            logger.info("Recuperati {} asset da Simple Earn Locked", balances.size());
            return balances;
        } catch (Exception e) {
            logger.error("Errore recupero Simple Earn Locked: {}", e.getMessage());
            return List.of();
        }
    }

    // Combina balances da più liste, sommando asset con lo stesso simbolo
    private List<CryptoBalance> combineBalances(List<CryptoBalance>... balanceLists) {
        Map<String, BigDecimal> combinedMap = new LinkedHashMap<>();
        for (List<CryptoBalance> balanceList : balanceLists) {
            for (CryptoBalance balance : balanceList) {
                combinedMap.merge(balance.getSymbol(), balance.getAmount(), BigDecimal::add);
            }
        }
        List<CryptoBalance> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : combinedMap.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) > 0) {
                result.add(CryptoBalance.builder().symbol(e.getKey()).amount(e.getValue()).build());
            }
        }
        return result;
    }

    // METODO PRINCIPALE: Recupera TUTTI gli asset (Spot + Earn Flexible + Earn Locked)
    public List<CryptoBalance> getAllWalletsIncludingEarn(String apiKey, String apiSecret) {
        logger.info("Recupero COMPLETO: Spot + Earn Flexible + Earn Locked...");
        try {
            List<CryptoBalance> spotBalances = getAccountBalances(apiKey, apiSecret);
            List<CryptoBalance> flexibleBalances = getSimpleEarnFlexibleBalances(apiKey, apiSecret);
            List<CryptoBalance> lockedBalances = getSimpleEarnLockedBalances(apiKey, apiSecret);
            List<CryptoBalance> combined = combineBalances(spotBalances, flexibleBalances, lockedBalances);
            logger.info("TOTALE COMBINATO: {} asset unici", combined.size());
            return combined;
        } catch (Exception e) {
            logger.error("Errore nel recupero completo: {}", e.getMessage(), e);
            return getAccountBalances(apiKey, apiSecret);
        }
    }
}
