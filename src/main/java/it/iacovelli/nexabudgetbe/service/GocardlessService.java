package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.dto.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
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
        this.restClient = RestClient.builder()
                .baseUrl(integratorBaseUrl)
                .messageConverters(httpMessageConverters -> {
                    httpMessageConverters.addFirst(converter);
                })
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
        logger.info("GocardlessService inizializzato con baseUrl: {}", integratorBaseUrl);
    }

    @Cacheable(value = GOCARDLESS_BANKS_CACHE, key = "#countryCode")
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

    public GocardlessCreateWebToken generateBankLinkForToken(String institutionId, UUID localAccountId) {
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
                return null;
            }

            logger.info("Token web generato con successo per institutionId: {}", institutionId);
            return createWebTokenResponse.getData();
        } catch (RestClientException e) {
            logger.error("Errore nella generazione del token web per institutionId: {}", institutionId, e);
            throw e;
        }
    }

    @Cacheable(value = BANK_ACCOUNTS_CACHE, key = "#requisitionId")
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

    @Cacheable(value = GOCARDLESS_TRANSACTIONS_CACHE, key = "#requisitionId + '_' + #accountId")
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
}
