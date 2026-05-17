package it.iacovelli.nexabudgetbe.service;

import com.coinbase.advanced.accounts.AccountsService;
import com.coinbase.advanced.client.CoinbaseAdvancedClient;
import com.coinbase.advanced.credentials.CoinbaseAdvancedCredentials;
import com.coinbase.advanced.errors.CoinbaseAdvancedException;
import com.coinbase.advanced.factory.CoinbaseAdvancedServiceFactory;
import com.coinbase.advanced.model.accounts.Account;
import com.coinbase.advanced.model.accounts.ListAccountsResponse;
import com.coinbase.advanced.model.portfolios.*;
import com.coinbase.advanced.portfolios.PortfoliosService;
import com.coinbase.advanced.utils.Constants;
import com.coinbase.core.errors.CoinbaseClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CoinbaseService {

    private static final Logger logger = LoggerFactory.getLogger(CoinbaseService.class);

    public List<CryptoBalance> getWallets(String apiKeyName, String privateKey) {
        logger.info("Avvio recupero integrale bilancio Coinbase (Account + Portafogli)...");
        try {
            // 1. Preparazione Credenziali (Formato JSON ultra-pulito)
            String cleanKey = privateKey.trim()
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .replace("\n", "\\n");
            
            String credentialsJson = String.format("{\"apiKeyName\": \"%s\", \"privateKey\": \"%s\"}", 
                    apiKeyName.trim(), cleanKey);
            
            CoinbaseAdvancedCredentials credentials = new CoinbaseAdvancedCredentials(credentialsJson);
            CoinbaseAdvancedClient client = new CoinbaseAdvancedClient(credentials);
            
            Map<String, BigDecimal> aggregatedBalances = new HashMap<>();
            Set<String> retailPortfolioIds = new HashSet<>();

            // 2. Scansione Account Standard
            try {
                AccountsService accountsService = CoinbaseAdvancedServiceFactory.createAccountsService(client);
                ListAccountsResponse accountsResponse = accountsService.listAccounts();
                if (accountsResponse != null && accountsResponse.getAccounts() != null) {
                    logger.info("Scansione Account Standard: trovati {} elementi", accountsResponse.getAccounts().size());
                    for (Account account : accountsResponse.getAccounts()) {
                        processAccount(account, aggregatedBalances);
                        String retailPortfolioId = account.getRetailPortfolioId();
                        if (retailPortfolioId != null && !retailPortfolioId.isBlank()) {
                            retailPortfolioIds.add(retailPortfolioId);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Impossibile scansionare account standard: {}", e.getMessage());
            }

            // 3. Scansione Portafogli (Fondamentale per asset in staking e sub-accounts)
            try {
                PortfoliosService portfoliosService = CoinbaseAdvancedServiceFactory.createPortfoliosService(client);
                Set<String> portfolioUuids = new HashSet<>();
                Map<String, String> portfolioNames = new HashMap<>();
                ListPortfoliosResponse portfoliosResponse = portfoliosService.listPortfolios(new ListPortfoliosRequest.Builder().build());
                
                if (portfoliosResponse != null && portfoliosResponse.getPortfolios() != null) {
                    logger.info("Trovati {} portafogli Coinbase da analizzare", portfoliosResponse.getPortfolios().size());
                    for (Portfolio portfolio : portfoliosResponse.getPortfolios()) {
                        if (portfolio.getUuid() != null && !portfolio.getUuid().isBlank()) {
                            portfolioUuids.add(portfolio.getUuid());
                            portfolioNames.put(portfolio.getUuid(), portfolio.getName());
                        }
                    }
                }

                Set<String> allPortfolioIds = new HashSet<>(portfolioUuids);
                allPortfolioIds.addAll(retailPortfolioIds);

                for (String portfolioId : allPortfolioIds) {
                    String portfolioLabel = portfolioNames.getOrDefault(portfolioId, "retail_portfolio_id");
                    processPortfolioBreakdown(portfoliosService, credentials, portfolioId, portfolioLabel, aggregatedBalances);
                }
            } catch (Exception e) {
                logger.warn("Impossibile scansionare portafogli: {}", e.getMessage());
            }

            List<CryptoBalance> balances = new ArrayList<>();
            aggregatedBalances.forEach((k, v) -> balances.add(new CryptoBalance(k, v)));
            
            logger.info("Sincronizzazione Coinbase terminata. Asset unici con saldo: {}. Simboli: {}", 
                    balances.size(), aggregatedBalances.keySet());
            
            return balances;
            
        } catch (Exception e) {
            logger.error("Errore critico durante l'integrazione Coinbase: {}", e.getMessage());
            throw new RuntimeException("Credenziali Coinbase non valide o errore di connessione.");
        }
    }

    private void processAccount(Account account, Map<String, BigDecimal> map) {
        BigDecimal available = account.getAvailableBalance() != null ? new BigDecimal(account.getAvailableBalance().getValue()) : BigDecimal.ZERO;
        BigDecimal hold = account.getHold() != null ? new BigDecimal(account.getHold().getValue()) : BigDecimal.ZERO;
        BigDecimal total = available.add(hold);

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            logger.info(">> SCOPERTO in Account: {} | Saldo: {} (Avail: {}, Hold: {})", 
                    account.getCurrency(), total, available, hold);
            map.merge(account.getCurrency().toUpperCase(), total, BigDecimal::add);
        }
    }

    private void processPortfolioBreakdown(
            PortfoliosService portfoliosService,
            CoinbaseAdvancedCredentials credentials,
            String portfolioUuid,
            String portfolioLabel,
            Map<String, BigDecimal> aggregatedBalances) {
        if (portfolioUuid == null || portfolioUuid.isBlank()) {
            return;
        }

        try {
            GetPortfolioBreakdownResponse breakdownResponse = portfoliosService.getPortfolioBreakdown(
                    new GetPortfolioBreakdownRequest(portfolioUuid));

            if (breakdownResponse == null || breakdownResponse.getBreakdown() == null) {
                return;
            }

            List<SpotPosition> spotPositions = breakdownResponse.getBreakdown().getSpotPositions();
            if (spotPositions == null) {
                return;
            }

            for (SpotPosition position : spotPositions) {
                BigDecimal total = BigDecimal.valueOf(position.getTotalBalanceCrypto());
                if (total.compareTo(BigDecimal.ZERO) > 0) {
                    logger.info(">> SCOPERTO in Portfolio '{}': {} | Saldo: {}",
                            portfolioLabel, position.getAsset(), total);
                    aggregatedBalances.merge(position.getAsset().toUpperCase(), total, BigDecimal::add);
                }
            }
            return;
        } catch (CoinbaseAdvancedException e) {
            logger.warn("Dettaglio non disponibile per portafoglio {} (uuid={}, status={}): {}",
                    portfolioLabel, portfolioUuid, e.getStatusCode(), e.getMessage(), e);
        } catch (CoinbaseClientException e) {
            if (isDeserializationFailure(e)) {
                logger.debug("Dettaglio SDK non leggibile per portafoglio {} (uuid={}): {}",
                        portfolioLabel, portfolioUuid, e.getMessage());
            } else {
                logger.warn("Dettaglio non disponibile per portafoglio {} (uuid={}): {}",
                        portfolioLabel, portfolioUuid, e.getMessage(), e);
            }
        } catch (Exception e) {
            logger.warn("Dettaglio non disponibile per portafoglio {} (uuid={}): {}",
                    portfolioLabel, portfolioUuid, e.getMessage(), e);
        }

        boolean fallbackOk = processPortfolioBreakdownRaw(credentials, portfolioUuid, portfolioLabel, aggregatedBalances);
        if (!fallbackOk) {
            logger.warn("Fallback breakdown fallito per portafoglio {} (uuid={})", portfolioLabel, portfolioUuid);
        }
    }

    private boolean processPortfolioBreakdownRaw(
            CoinbaseAdvancedCredentials credentials,
            String portfolioUuid,
            String portfolioLabel,
            Map<String, BigDecimal> aggregatedBalances) {
        try {
            URI uri = URI.create(Constants.BASE_URL + "/brokerage/portfolios/" + portfolioUuid);

            Map<String, String> authHeaders = credentials.generateAuthHeaders("GET", uri, "");
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
            for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("Fallback breakdown HTTP {} per portafoglio {} (uuid={}): {}",
                        response.statusCode(), portfolioLabel, portfolioUuid, response.body());
                return false;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());
            JsonNode spotPositions = root.path("breakdown").path("spot_positions");
            if (!spotPositions.isArray()) {
                return false;
            }

            for (JsonNode position : spotPositions) {
                String asset = position.path("asset").asText(null);
                if (asset == null || asset.isBlank()) {
                    continue;
                }
                if (position.path("is_cash").asBoolean(false)) {
                    continue;
                }
                BigDecimal total = parseDecimal(position.get("total_balance_crypto"));
                if (total.compareTo(BigDecimal.ZERO) > 0) {
                    logger.info(">> SCOPERTO in Portfolio '{}': {} | Saldo: {}",
                            portfolioLabel, asset, total);
                    aggregatedBalances.merge(asset.toUpperCase(), total, BigDecimal::add);
                }
            }

            return true;
        } catch (Exception e) {
            logger.warn("Fallback breakdown non riuscito per portafoglio {} (uuid={}): {}",
                    portfolioLabel, portfolioUuid, e.getMessage(), e);
            return false;
        }
    }

    private BigDecimal parseDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText());
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private boolean isDeserializationFailure(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof MismatchedInputException) {
            return true;
        }
        String message = throwable.getMessage();
        if (message != null && message.contains("Failed to deserialize class")) {
            return true;
        }
        return isDeserializationFailure(throwable.getCause());
    }
}
