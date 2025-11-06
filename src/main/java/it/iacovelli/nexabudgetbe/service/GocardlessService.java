package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.dto.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Service
public class GocardlessService {

    @Value("${gocardless.integrator.baseUrl}")
    private String integratorBaseUrl;

    private RestClient restClient;

    private final ObjectMapper objectMapper;

    public GocardlessService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    private void init() {
        this.restClient = RestClient.builder()
                .baseUrl(integratorBaseUrl)
                .messageConverters(httpMessageConverters -> {
                    httpMessageConverters.clear();
                    httpMessageConverters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                })
                .build();
    }

    public List<GocardlessBank> getBanks(String countryCode) {
        String path = "/get-banks";
        GocardlessGetBanksRequest request = new GocardlessGetBanksRequest();
        request.setCountry(countryCode);
        GocardlessGetBanksResponse banksResponse = restClient.post()
                .uri(path)
                .body(request)
                .retrieve()
                .body(GocardlessGetBanksResponse.class);
        return banksResponse != null ? banksResponse.getData() : List.of();
    }

    public GocardlessCreateWebToken generateBankLinkForToken(String institutionId, UUID localAccountId) {
        String path = "/create-web-token";
        GocardlessCreateWebTokenRequest request = new GocardlessCreateWebTokenRequest();
        request.setInstitutionId(institutionId);
        request.setLocalAccountId(localAccountId);
        GocardlessCreateWebTokenResponse createWebTokenResponse = restClient.post().uri(path).body(request).retrieve().body(GocardlessCreateWebTokenResponse.class);
        if (createWebTokenResponse == null) {
            return null;
        }
        return createWebTokenResponse.getData();
    }

    public List<GocardlessBankDetail> getBankAccounts(String requisitionId) {
        String path = "/get-accounts";
        GocardlessGetAccountsRequest request = new GocardlessGetAccountsRequest();
        request.setRequisitionId(requisitionId);
        GocardlessGetAccountsResponse accountsResponse = restClient.post().uri(path).body(request).retrieve().body(GocardlessGetAccountsResponse.class);
        if (accountsResponse == null) {
            return List.of();
        }
        GocardlessGetAccounts accountsResponseData = accountsResponse.getData();
        return accountsResponseData != null ? accountsResponseData.getAccounts() : List.of();
    }

    public List<GocardlessTransaction> getGoCardlessTransaction(String requisitionId, String accountId) {
        String path = "/transactions";
        GocardlessGetTransactionsRequest request = new GocardlessGetTransactionsRequest();
        request.setRequisitionId(requisitionId);
        request.setAccountId(accountId);
        GocardlessGetTransactionsResponse transactionsResponse = restClient.post().uri(path).body(request).retrieve().body(GocardlessGetTransactionsResponse.class);
        if (transactionsResponse == null) {
            return List.of();
        }
        GocardlessBankTransaction transactionsResponseData = transactionsResponse.getData();
        if (transactionsResponseData == null) {
            return List.of();
        }

        return transactionsResponseData.getTransactions() != null ? transactionsResponseData.getTransactions().getAll() : List.of();
    }


}
