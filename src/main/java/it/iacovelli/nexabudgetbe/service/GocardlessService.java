package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.dto.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static it.iacovelli.nexabudgetbe.config.CacheConfig.*;

@RegisterReflectionForBinding(classes = {
        GocardlessGetBanksRequest.class,
        GocardlessGetBanksResponse.class,
        GocardlessCreateWebTokenRequest.class,
        GocardlessCreateWebTokenResponse.class,
        GocardlessGetAccountsRequest.class,
        GocardlessGetAccountsResponse.class,
        GocardlessGetTransactionsRequest.class,
        GocardlessGetTransactionsResponse.class,
        GocardlessBaseResponse.class,
        GocardlessBank.class,
        GocardlessCreateWebToken.class,
        GocardlessGetAccounts.class,
        GocardlessBankDetail.class,
        GocardlessBankTransaction.class,
        GocardlessTransactions.class,
        GocardlessTransaction.class,
        GocardlessBalance.class,
        GocardlessAmount.class
})
@Service
public class GocardlessService {

    private static final Logger logger = LoggerFactory.getLogger(GocardlessService.class);

    @Value("${gocardless.integrator.baseUrl}")
    private String integratorBaseUrl;

    private RestClient restClient;

    private final ObjectMapper objectMapper;

    public GocardlessService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    private void init() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(integratorBaseUrl)
                .requestFactory(factory)
                .messageConverters(httpMessageConverters -> {
                    httpMessageConverters.addFirst(converter);
                })
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
        logger.info("GocardlessService inizializzato con baseUrl: {}", integratorBaseUrl);
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    @Cacheable(value = GOCARDLESS_BANKS_CACHE, key = "#countryCode", unless = "#result.isEmpty()")
    public List<GocardlessBank> getBanks(String countryCode) {
        logger.info("Recupero lista banche per il paese: {}", countryCode);
        try {
            String path = "/get-banks";
            GocardlessGetBanksRequest request = new GocardlessGetBanksRequest();
            request.setCountry(countryCode);

            GocardlessGetBanksResponse banksResponse = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(GocardlessGetBanksResponse.class);

            int bankCount = banksResponse != null && banksResponse.getData() != null ? banksResponse.getData().size() : 0;
            logger.info("Recuperate {} banche per il paese: {}", bankCount, countryCode);

            return banksResponse != null ? banksResponse.getData() : List.of();
        } catch (RestClientException e) {
            logger.error("Errore nel recupero delle banche per il paese: {}", countryCode, e);
            throw e;
        }
    }

    @Recover
    public List<GocardlessBank> recoverGetBanks(RestClientException e, String countryCode) {
        logger.error("Impossibile recuperare banche per {} dopo i retry: {}", countryCode, e.getMessage());
        return List.of();
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public Optional<GocardlessCreateWebToken> generateBankLinkForToken(String institutionId, UUID localAccountId) {
        logger.info("Generazione link bancario per institutionId: {}, accountId: {}", institutionId, localAccountId);
        try {
            String path = "/create-web-token";
            GocardlessCreateWebTokenRequest request = new GocardlessCreateWebTokenRequest();
            request.setInstitutionId(institutionId);
            request.setLocalAccountId(localAccountId);

            GocardlessCreateWebTokenResponse createWebTokenResponse = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(GocardlessCreateWebTokenResponse.class);

            if (createWebTokenResponse == null || createWebTokenResponse.getData() == null) {
                logger.warn("Risposta vuota nella generazione del token web per institutionId: {}", institutionId);
                return Optional.empty();
            }

            logger.info("Token web generato con successo per institutionId: {}", institutionId);
            return Optional.of(createWebTokenResponse.getData());
        } catch (RestClientException e) {
            logger.error("Errore nella generazione del token web per institutionId: {}", institutionId, e);
            throw e;
        }
    }

    @Recover
    public Optional<GocardlessCreateWebToken> recoverGenerateBankLinkForToken(RestClientException e, String institutionId, UUID localAccountId) {
        logger.error("Impossibile generare token web per {} dopo i retry: {}", institutionId, e.getMessage());
        return Optional.empty();
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    @Cacheable(value = BANK_ACCOUNTS_CACHE, key = "#requisitionId", unless = "#result.isEmpty()")
    public List<GocardlessBankDetail> getBankAccounts(String requisitionId) {
        logger.info("Recupero conti bancari per requisitionId: {}", requisitionId);
        try {
            String path = "/get-accounts";
            GocardlessGetAccountsRequest request = new GocardlessGetAccountsRequest();
            request.setRequisitionId(requisitionId);

            GocardlessGetAccountsResponse accountsResponse = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(GocardlessGetAccountsResponse.class);

            if (accountsResponse == null) {
                logger.warn("Risposta vuota nel recupero dei conti per requisitionId: {}", requisitionId);
                return List.of();
            }

            GocardlessGetAccounts accountsResponseData = accountsResponse.getData();
            int accountCount = accountsResponseData != null && accountsResponseData.getAccounts() != null ? 
                    accountsResponseData.getAccounts().size() : 0;
            logger.info("Recuperati {} conti bancari per requisitionId: {}", accountCount, requisitionId);

            return accountsResponseData != null ? accountsResponseData.getAccounts() : List.of();
        } catch (RestClientException e) {
            logger.error("Errore nel recupero dei conti per requisitionId: {}", requisitionId, e);
            throw e;
        }
    }

    @Recover
    public List<GocardlessBankDetail> recoverGetBankAccounts(RestClientException e, String requisitionId) {
        logger.error("Impossibile recuperare conti per {} dopo i retry: {}", requisitionId, e.getMessage());
        return List.of();
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    @Cacheable(value = GOCARDLESS_TRANSACTIONS_CACHE, key = "#requisitionId + '_' + #accountId", unless = "#result.isEmpty()")
    public List<GocardlessTransaction> getGoCardlessTransaction(String requisitionId, String accountId) {
        logger.info("Recupero transazioni per requisitionId: {}, accountId: {}", requisitionId, accountId);
        try {
            String path = "/transactions";
            GocardlessGetTransactionsRequest request = new GocardlessGetTransactionsRequest();
            request.setRequisitionId(requisitionId);
            request.setAccountId(accountId);

            GocardlessGetTransactionsResponse transactionsResponse = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(GocardlessGetTransactionsResponse.class);

            if (transactionsResponse == null) {
                logger.warn("Risposta vuota nel recupero delle transazioni per accountId: {}", accountId);
                return List.of();
            }

            GocardlessBankTransaction transactionsResponseData = transactionsResponse.getData();
            if (transactionsResponseData == null) {
                logger.warn("Dati transazioni vuoti per accountId: {}", accountId);
                return List.of();
            }

            List<GocardlessTransaction> transactions = transactionsResponseData.getTransactions() != null ? 
                    transactionsResponseData.getTransactions().getAll() : List.of();
            logger.info("Recuperate {} transazioni per accountId: {}", transactions.size(), accountId);

            return transactions;
        } catch (RestClientException e) {
            logger.error("Errore nel recupero delle transazioni per accountId: {}", accountId, e);
            throw e;
        }
    }

    @Recover
    public List<GocardlessTransaction> recoverGetGoCardlessTransaction(RestClientException e, String requisitionId, String accountId) {
        logger.error("Impossibile recuperare transazioni per accountId {} dopo i retry: {}", accountId, e.getMessage());
        return List.of();
    }
}
