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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import it.iacovelli.nexabudgetbe.dto.CryptoBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
public class CoinbaseService {

    private static final Logger logger = LoggerFactory.getLogger(CoinbaseService.class);

    // Tetto di sicurezza alla paginazione di /brokerage/accounts (49 account per pagina di default)
    static final int MAX_ACCOUNT_PAGES = 50;
    static final String FIAT_ACCOUNT_TYPE = "ACCOUNT_TYPE_FIAT";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // L'SDK di default usa un HttpClient senza timeout: una chiamata appesa bloccherebbe la sync
    // (e il relativo lock in CryptoPortfolioService) a tempo indeterminato
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * Esito del breakdown di un singolo portafoglio: saldi per asset (fiat esclusi) e uuid degli account coperti.
     */
    record BreakdownResult(String portfolioId, boolean ok, Map<String, BigDecimal> balances, Set<String> accountUuids) {
        static BreakdownResult failed(String portfolioId) {
            return new BreakdownResult(portfolioId, false, Map.of(), Set.of());
        }
    }

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
            CoinbaseAdvancedClient client = new CoinbaseAdvancedClient(credentials, httpClient);

            // 2. Scansione Account Standard (tutte le pagine): usata solo come fallback dei breakdown
            List<Account> accounts = null;
            Set<String> retailPortfolioIds = new LinkedHashSet<>();
            try {
                AccountsService accountsService = CoinbaseAdvancedServiceFactory.createAccountsService(client);
                accounts = collectAllAccounts(accountsService.listAccounts(),
                        cursor -> fetchAccountsPage(credentials, cursor));
                logger.info("Scansione Account Standard: trovati {} elementi", accounts.size());
                for (Account account : accounts) {
                    String retailPortfolioId = account.getRetailPortfolioId();
                    if (retailPortfolioId != null && !retailPortfolioId.isBlank()) {
                        retailPortfolioIds.add(retailPortfolioId);
                    }
                }
            } catch (Exception e) {
                // Scansione parziale = scansione fallita: un elenco incompleto farebbe sparire degli holdings
                accounts = null;
                logger.warn("Impossibile scansionare account standard: {}", e.getMessage(), e);
            }

            // 3. Scansione Portafogli (fonte primaria: include asset in staking e sub-accounts)
            boolean portfolioListOk = false;
            List<BreakdownResult> breakdowns = new ArrayList<>();
            try {
                PortfoliosService portfoliosService = CoinbaseAdvancedServiceFactory.createPortfoliosService(client);
                Map<String, String> portfolioNames = new HashMap<>();
                Set<String> allPortfolioIds = new LinkedHashSet<>();
                try {
                    ListPortfoliosResponse portfoliosResponse = portfoliosService.listPortfolios(new ListPortfoliosRequest.Builder().build());
                    if (portfoliosResponse != null && portfoliosResponse.getPortfolios() != null) {
                        logger.info("Trovati {} portafogli Coinbase da analizzare", portfoliosResponse.getPortfolios().size());
                        for (Portfolio portfolio : portfoliosResponse.getPortfolios()) {
                            if (portfolio.getUuid() != null && !portfolio.getUuid().isBlank()) {
                                allPortfolioIds.add(portfolio.getUuid());
                                portfolioNames.put(portfolio.getUuid(), portfolio.getName());
                            }
                        }
                    }
                    portfolioListOk = true;
                } catch (Exception e) {
                    logger.warn("Impossibile elencare i portafogli Coinbase: {}", e.getMessage(), e);
                }

                allPortfolioIds.addAll(retailPortfolioIds);

                for (String portfolioId : allPortfolioIds) {
                    String portfolioLabel = portfolioNames.getOrDefault(portfolioId, "retail_portfolio_id");
                    breakdowns.add(fetchPortfolioBreakdown(portfoliosService, credentials, portfolioId, portfolioLabel));
                }
            } catch (Exception e) {
                logger.warn("Impossibile scansionare portafogli: {}", e.getMessage(), e);
            }

            // 4. Una sola fonte per asset: breakdown dove riusciti, account scan per il resto.
            // Se i dati non sono completi lancia: una lista parziale farebbe cancellare al chiamante
            // gli holdings Coinbase mancanti
            List<CryptoBalance> balances = mergeSources(accounts, portfolioListOk, breakdowns);

            logger.info("Sincronizzazione Coinbase terminata. Asset unici con saldo: {}. Simboli: {}",
                    balances.size(), balances.stream().map(CryptoBalance::getSymbol).toList());

            return balances;

        } catch (Exception e) {
            logger.error("Errore critico durante l'integrazione Coinbase: {}", e.getMessage());
            throw new RuntimeException("Credenziali Coinbase non valide o errore di connessione.");
        }
    }

    /**
     * Unisce le due fonti senza contare due volte lo stesso asset:
     * <ul>
     *   <li>i breakdown riusciti sono la fonte primaria;</li>
     *   <li>un account entra nel totale solo se il suo portafoglio (retailPortfolioId) non ha un breakdown riuscito
     *       e il suo uuid non compare tra le spot position già contate; se nessun breakdown è riuscito
     *       si usa l'intera account scan (fallback globale, inclusi gli account senza retailPortfolioId);</li>
     *   <li>i fiat (is_cash / ACCOUNT_TYPE_FIAT) sono esclusi da entrambe le fonti.</li>
     * </ul>
     *
     * @param accounts account scan completa, oppure {@code null} se la scansione è fallita
     * @throws IllegalStateException se le risposte non bastano a ricostruire tutti i saldi
     */
    List<CryptoBalance> mergeSources(List<Account> accounts, boolean portfolioListOk, List<BreakdownResult> breakdowns) {
        Map<String, BigDecimal> aggregated = new HashMap<>();
        Set<String> okPortfolios = new HashSet<>();
        Set<String> coveredAccountUuids = new HashSet<>();
        boolean anyBreakdownFailed = false;

        for (BreakdownResult breakdown : breakdowns) {
            if (!breakdown.ok()) {
                anyBreakdownFailed = true;
                continue;
            }
            okPortfolios.add(breakdown.portfolioId());
            coveredAccountUuids.addAll(breakdown.accountUuids());
            // Somma tra portafogli diversi: lo stesso asset può stare in più portafogli
            breakdown.balances().forEach((asset, amount) -> aggregated.merge(asset, amount, BigDecimal::add));
        }
        boolean anyBreakdownOk = !okPortfolios.isEmpty();

        if (accounts == null) {
            // Senza account scan non c'è fallback: servono l'elenco portafogli e tutti i breakdown
            if (!portfolioListOk || !anyBreakdownOk || anyBreakdownFailed) {
                throw new IllegalStateException("Nessuna risposta completa da Coinbase");
            }
        } else {
            if (!anyBreakdownOk) {
                logger.warn("Nessun breakdown di portafoglio riuscito: uso i saldi dell'account scan");
            }
            for (Account account : accounts) {
                if (!anyBreakdownOk || !isCoveredByBreakdown(account, okPortfolios, coveredAccountUuids)) {
                    processAccount(account, aggregated);
                }
            }
        }

        List<CryptoBalance> balances = new ArrayList<>();
        aggregated.forEach((k, v) -> balances.add(new CryptoBalance(k, v)));
        return balances;
    }

    private boolean isCoveredByBreakdown(Account account, Set<String> okPortfolios, Set<String> coveredAccountUuids) {
        String retailPortfolioId = account.getRetailPortfolioId();
        if (retailPortfolioId == null || retailPortfolioId.isBlank()) {
            // Portafoglio non noto: non si può escludere che sia già nei breakdown, quindi non lo si somma
            return true;
        }
        return okPortfolios.contains(retailPortfolioId)
                || (account.getUuid() != null && coveredAccountUuids.contains(account.getUuid()));
    }

    private void processAccount(Account account, Map<String, BigDecimal> map) {
        if (account.getCurrency() == null || FIAT_ACCOUNT_TYPE.equals(account.getType())) {
            return;
        }
        BigDecimal available = account.getAvailableBalance() != null ? new BigDecimal(account.getAvailableBalance().getValue()) : BigDecimal.ZERO;
        BigDecimal hold = account.getHold() != null ? new BigDecimal(account.getHold().getValue()) : BigDecimal.ZERO;
        BigDecimal total = available.add(hold);

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            logger.info(">> SCOPERTO in Account: {} | Saldo: {} (Avail: {}, Hold: {})",
                    account.getCurrency(), total, available, hold);
            map.merge(account.getCurrency().toUpperCase(), total, BigDecimal::add);
        }
    }

    /**
     * Segue il cursore di /brokerage/accounts fino all'ultima pagina. L'SDK espone solo la prima pagina,
     * le successive vengono chieste da {@code nextPageFetcher}. Lancia se l'elenco risulta incompleto.
     */
    static List<Account> collectAllAccounts(ListAccountsResponse firstPage,
                                            Function<String, ListAccountsResponse> nextPageFetcher) {
        List<Account> accounts = new ArrayList<>();
        ListAccountsResponse page = firstPage;
        int pages = 0;
        while (page != null) {
            pages++;
            if (page.getAccounts() != null) {
                accounts.addAll(page.getAccounts());
            }
            if (!page.isHasNext()) {
                return accounts;
            }
            String cursor = page.getCursor();
            if (cursor == null || cursor.isBlank()) {
                throw new IllegalStateException("Paginazione account Coinbase: has_next senza cursore");
            }
            if (pages >= MAX_ACCOUNT_PAGES) {
                throw new IllegalStateException("Paginazione account Coinbase oltre " + MAX_ACCOUNT_PAGES + " pagine");
            }
            page = nextPageFetcher.apply(cursor);
        }
        throw new IllegalStateException("Pagina account Coinbase vuota");
    }

    private ListAccountsResponse fetchAccountsPage(CoinbaseAdvancedCredentials credentials, String cursor) {
        try {
            // Il JWT firma solo metodo+host+path: la query string non entra nella firma
            URI uri = URI.create(Constants.BASE_URL + "/brokerage/accounts?cursor="
                    + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            HttpResponse<String> response = sendSigned(credentials, uri);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " su pagina account Coinbase");
            }
            return objectMapper.readValue(response.body(), ListAccountsResponse.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Pagina account Coinbase non leggibile: " + e.getMessage(), e);
        }
    }

    private BreakdownResult fetchPortfolioBreakdown(
            PortfoliosService portfoliosService,
            CoinbaseAdvancedCredentials credentials,
            String portfolioUuid,
            String portfolioLabel) {
        if (portfolioUuid == null || portfolioUuid.isBlank()) {
            return BreakdownResult.failed(portfolioUuid);
        }

        try {
            GetPortfolioBreakdownResponse breakdownResponse = portfoliosService.getPortfolioBreakdown(
                    new GetPortfolioBreakdownRequest(portfolioUuid));

            if (breakdownResponse != null && breakdownResponse.getBreakdown() != null) {
                return fromSpotPositions(portfolioUuid, portfolioLabel, breakdownResponse.getBreakdown().getSpotPositions());
            }
            logger.warn("Dettaglio vuoto per portafoglio {} (uuid={}), provo il fallback", portfolioLabel, portfolioUuid);
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

        BreakdownResult fallback = fetchPortfolioBreakdownRaw(credentials, portfolioUuid, portfolioLabel);
        if (!fallback.ok()) {
            logger.warn("Fallback breakdown fallito per portafoglio {} (uuid={})", portfolioLabel, portfolioUuid);
        }
        return fallback;
    }

    /**
     * Breakdown letto dal modello SDK. I fiat (is_cash) sono esclusi come nel fallback HTTP.
     */
    BreakdownResult fromSpotPositions(String portfolioUuid, String portfolioLabel, List<SpotPosition> spotPositions) {
        Map<String, BigDecimal> balances = new HashMap<>();
        Set<String> accountUuids = new HashSet<>();
        if (spotPositions != null) {
            for (SpotPosition position : spotPositions) {
                if (position == null || position.getAsset() == null || position.getAsset().isBlank() || position.isCash()) {
                    continue;
                }
                addPosition(portfolioLabel, position.getAsset(), position.getAccountUuid(),
                        BigDecimal.valueOf(position.getTotalBalanceCrypto()), balances, accountUuids);
            }
        }
        return new BreakdownResult(portfolioUuid, true, balances, accountUuids);
    }

    private BreakdownResult fetchPortfolioBreakdownRaw(
            CoinbaseAdvancedCredentials credentials,
            String portfolioUuid,
            String portfolioLabel) {
        try {
            URI uri = URI.create(Constants.BASE_URL + "/brokerage/portfolios/" + portfolioUuid);
            HttpResponse<String> response = sendSigned(credentials, uri);

            if (response.statusCode() != 200) {
                logger.warn("Fallback breakdown HTTP {} per portafoglio {} (uuid={}): {}",
                        response.statusCode(), portfolioLabel, portfolioUuid, response.body());
                return BreakdownResult.failed(portfolioUuid);
            }

            return parseRawBreakdown(portfolioUuid, portfolioLabel, response.body());
        } catch (Exception e) {
            logger.warn("Fallback breakdown non riuscito per portafoglio {} (uuid={}): {}",
                    portfolioLabel, portfolioUuid, e.getMessage(), e);
            return BreakdownResult.failed(portfolioUuid);
        }
    }

    /**
     * Breakdown letto dal JSON grezzo di /brokerage/portfolios/{uuid}, per quando il modello SDK non deserializza.
     */
    BreakdownResult parseRawBreakdown(String portfolioUuid, String portfolioLabel, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode spotPositions = root.path("breakdown").path("spot_positions");
            if (!spotPositions.isArray()) {
                return BreakdownResult.failed(portfolioUuid);
            }

            Map<String, BigDecimal> balances = new HashMap<>();
            Set<String> accountUuids = new HashSet<>();
            for (JsonNode position : spotPositions) {
                String asset = position.path("asset").asText(null);
                if (asset == null || asset.isBlank()) {
                    continue;
                }
                if (position.path("is_cash").asBoolean(false)) {
                    continue;
                }
                addPosition(portfolioLabel, asset, position.path("account_uuid").asText(null),
                        parseDecimal(position.get("total_balance_crypto")), balances, accountUuids);
            }
            return new BreakdownResult(portfolioUuid, true, balances, accountUuids);
        } catch (Exception e) {
            logger.warn("Breakdown non leggibile per portafoglio {} (uuid={}): {}",
                    portfolioLabel, portfolioUuid, e.getMessage());
            return BreakdownResult.failed(portfolioUuid);
        }
    }

    private void addPosition(String portfolioLabel, String asset, String accountUuid, BigDecimal total,
                             Map<String, BigDecimal> balances, Set<String> accountUuids) {
        if (accountUuid != null && !accountUuid.isBlank()) {
            accountUuids.add(accountUuid);
        }
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            logger.info(">> SCOPERTO in Portfolio '{}': {} | Saldo: {}", portfolioLabel, asset, total);
            balances.merge(asset.toUpperCase(), total, BigDecimal::add);
        }
    }

    private HttpResponse<String> sendSigned(CoinbaseAdvancedCredentials credentials, URI uri) throws Exception {
        Map<String, String> authHeaders = credentials.generateAuthHeaders("GET", uri, "");
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT);
        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }
        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
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
