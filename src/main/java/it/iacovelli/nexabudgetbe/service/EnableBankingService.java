package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import it.iacovelli.nexabudgetbe.dto.enablebanking.*;
import it.iacovelli.nexabudgetbe.exception.BankReauthRequiredException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static it.iacovelli.nexabudgetbe.config.CacheConfig.ENABLE_BANKING_ASPSPS_CACHE;
import static it.iacovelli.nexabudgetbe.config.CacheConfig.ENABLE_BANKING_TRANSACTIONS_CACHE;

/**
 * Client per la Cloud API di Enable Banking (https://api.enablebanking.com), chiamata direttamente
 * da questa applicazione (a differenza di GoCardless, non c'è un integrator intermedio).
 * Autenticazione via JWT RS256 firmato con la private key dell'applicazione registrata sul control panel
 * di Enable Banking (app_id + private key, entrambi da env var).
 */
@RegisterReflectionForBinding(classes = {
        EnableBankingAspsp.class,
        EnableBankingAspspsResponse.class,
        EnableBankingAspspRef.class,
        EnableBankingAccess.class,
        EnableBankingAuthRequest.class,
        EnableBankingAuthResponse.class,
        EnableBankingSessionRequest.class,
        EnableBankingSessionResponse.class,
        EnableBankingAccount.class,
        EnableBankingAccountId.class,
        EnableBankingAmount.class,
        EnableBankingParty.class,
        EnableBankingTransaction.class,
        EnableBankingTransactionsResponse.class
})
@Service
public class EnableBankingService {

    private static final Logger logger = LoggerFactory.getLogger(EnableBankingService.class);

    /** Margine di sicurezza prima della scadenza del JWT applicativo per forzarne la rigenerazione. */
    private static final Duration TOKEN_EXPIRY_SAFETY_MARGIN = Duration.ofMinutes(2);
    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    /** Limite di pagine per singola sincronizzazione, a protezione da loop infiniti sul continuation_key. */
    private static final int MAX_TRANSACTION_PAGES = 50;

    @Value("${enablebanking.baseUrl}")
    private String baseUrl;

    @Value("${enablebanking.appId}")
    private String appId;

    @Value("${enablebanking.privateKey}")
    private String privateKeyPem;

    private final ObjectMapper objectMapper;

    private RestClient restClient;
    private PrivateKey privateKey;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public EnableBankingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    private void init() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .messageConverters(converters -> converters.addFirst(converter))
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
        this.privateKey = parsePrivateKey(privateKeyPem);
        logger.info("EnableBankingService inizializzato con baseUrl: {}", baseUrl);
    }

    /**
     * Carica una chiave privata RSA in formato PEM PKCS8 (header "-----BEGIN PRIVATE KEY-----").
     * L'env var può contenere "\n" letterali al posto di newline reali (comodo per i secret manager):
     * vengono normalizzati prima del parsing.
     */
    private PrivateKey parsePrivateKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n");
            String base64Only = normalized
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64Only);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Impossibile caricare ENABLEBANKING_PRIVATE_KEY: atteso formato PEM PKCS8 (-----BEGIN PRIVATE KEY-----)", e);
        }
    }

    /**
     * JWT applicativo RS256 richiesto su ogni chiamata alla Cloud API Enable Banking.
     * Cache in memoria fino a poco prima della scadenza per evitare di rifirmare ad ogni richiesta.
     */
    private synchronized String currentToken() {
        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(cachedTokenExpiry.minus(TOKEN_EXPIRY_SAFETY_MARGIN))) {
            return cachedToken;
        }
        Date issuedAt = Date.from(now);
        Date expiration = Date.from(now.plus(TOKEN_TTL));

        cachedToken = Jwts.builder()
                .header().keyId(appId).and()
                .issuer("enablebanking.com")
                .audience().add("api.enablebanking.com").and()
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        cachedTokenExpiry = now.plus(TOKEN_TTL);
        return cachedToken;
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    @Cacheable(value = ENABLE_BANKING_ASPSPS_CACHE, key = "#countryCode", unless = "#result.size() == 0")
    public List<EnableBankingAspsp> getAspsps(String countryCode) {
        logger.info("Recupero lista ASPSP Enable Banking per il paese: {}", countryCode);
        try {
            EnableBankingAspspsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/aspsps").queryParam("country", countryCode).build())
                    .header("Authorization", "Bearer " + currentToken())
                    .retrieve()
                    .body(EnableBankingAspspsResponse.class);

            List<EnableBankingAspsp> aspsps = response != null && response.getAspsps() != null
                    ? response.getAspsps() : new ArrayList<>();
            logger.info("Recuperati {} ASPSP Enable Banking per il paese: {}", aspsps.size(), countryCode);
            return aspsps;
        } catch (RestClientException e) {
            logger.error("Errore nel recupero degli ASPSP Enable Banking per il paese: {}", countryCode, e);
            throw e;
        }
    }

    @Recover
    public List<EnableBankingAspsp> recoverGetAspsps(RestClientException e, String countryCode) {
        logger.error("Impossibile recuperare ASPSP Enable Banking per {} dopo i retry: {}", countryCode, e.getMessage());
        return new ArrayList<>();
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String startAuthorization(String aspspName, String aspspCountry, String redirectUrl, String state, int validForDays) {
        logger.info("Avvio autorizzazione Enable Banking per ASPSP {}/{}", aspspName, aspspCountry);
        try {
            EnableBankingAuthRequest request = EnableBankingAuthRequest.builder()
                    .access(new EnableBankingAccess(
                            DateTimeFormatter.ISO_INSTANT.format(Instant.now().plus(Duration.ofDays(validForDays)))))
                    .aspsp(new EnableBankingAspspRef(aspspName, aspspCountry))
                    .state(state)
                    .redirectUrl(redirectUrl)
                    .psuType("personal")
                    .build();

            EnableBankingAuthResponse response = restClient.post()
                    .uri("/auth")
                    .header("Authorization", "Bearer " + currentToken())
                    .body(request)
                    .retrieve()
                    .body(EnableBankingAuthResponse.class);

            if (response == null || response.getUrl() == null) {
                throw new IllegalStateException("Risposta vuota da Enable Banking /auth");
            }
            return response.getUrl();
        } catch (RestClientException e) {
            logger.error("Errore nell'avvio dell'autorizzazione Enable Banking per {}/{}", aspspName, aspspCountry, e);
            throw e;
        }
    }

    @Recover
    public String recoverStartAuthorization(RestClientException e, String aspspName, String aspspCountry, String redirectUrl, String state, int validForDays) {
        logger.error("Impossibile avviare autorizzazione Enable Banking per {}/{} dopo i retry: {}", aspspName, aspspCountry, e.getMessage());
        throw new IllegalStateException("Servizio Enable Banking non disponibile, riprovare più tardi", e);
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public EnableBankingSessionResponse createSession(String code) {
        logger.info("Creazione sessione Enable Banking da authorization code");
        try {
            EnableBankingSessionResponse response = restClient.post()
                    .uri("/sessions")
                    .header("Authorization", "Bearer " + currentToken())
                    .body(new EnableBankingSessionRequest(code))
                    .retrieve()
                    .body(EnableBankingSessionResponse.class);

            if (response == null || response.getSessionId() == null) {
                throw new IllegalStateException("Risposta vuota da Enable Banking /sessions");
            }
            logger.info("Sessione Enable Banking creata: {} con {} conti", response.getSessionId(),
                    response.getAccounts() != null ? response.getAccounts().size() : 0);
            return response;
        } catch (RestClientException e) {
            logger.error("Errore nella creazione della sessione Enable Banking", e);
            throw e;
        }
    }

    @Recover
    public EnableBankingSessionResponse recoverCreateSession(RestClientException e, String code) {
        logger.error("Impossibile creare la sessione Enable Banking dopo i retry: {}", e.getMessage());
        throw new IllegalStateException("Servizio Enable Banking non disponibile, riprovare più tardi", e);
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    @Cacheable(value = ENABLE_BANKING_TRANSACTIONS_CACHE, key = "#accountUid", unless = "#result.size() == 0")
    public List<EnableBankingTransaction> getTransactions(String accountUid, String dateFrom) {
        logger.info("Recupero transazioni Enable Banking per account uid: {}", accountUid);
        List<EnableBankingTransaction> all = new ArrayList<>();
        String continuationKey = null;
        int page = 0;
        try {
            do {
                String finalContinuationKey = continuationKey;
                EnableBankingTransactionsResponse response = restClient.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path("/accounts/{uid}/transactions");
                            if (dateFrom != null) {
                                uriBuilder.queryParam("date_from", dateFrom);
                            }
                            if (finalContinuationKey != null) {
                                uriBuilder.queryParam("continuation_key", finalContinuationKey);
                            }
                            return uriBuilder.build(accountUid);
                        })
                        .header("Authorization", "Bearer " + currentToken())
                        .retrieve()
                        .onStatus(status -> status.value() == 401 || status.value() == 403,
                                (req, res) -> {
                                    throw new BankReauthRequiredException(
                                            "Sessione Enable Banking non più valida per account " + accountUid,
                                            String.valueOf(res.getStatusCode().value()), "SESSION_EXPIRED", true);
                                })
                        .body(EnableBankingTransactionsResponse.class);

                if (response == null || response.getTransactions() == null) {
                    break;
                }
                all.addAll(response.getTransactions());
                continuationKey = response.getContinuationKey();
                page++;
            } while (continuationKey != null && page < MAX_TRANSACTION_PAGES);

            if (page >= MAX_TRANSACTION_PAGES) {
                logger.warn("Raggiunto il limite di {} pagine per account uid: {}, ulteriori transazioni ignorate in questo giro",
                        MAX_TRANSACTION_PAGES, accountUid);
            }

            logger.info("Recuperate {} transazioni Enable Banking per account uid: {}", all.size(), accountUid);
            return all;
        } catch (BankReauthRequiredException e) {
            throw e;
        } catch (RestClientException e) {
            logger.error("Errore nel recupero delle transazioni Enable Banking per account uid: {}", accountUid, e);
            throw e;
        }
    }

    @Recover
    public List<EnableBankingTransaction> recoverGetTransactions(RestClientException e, String accountUid, String dateFrom) {
        logger.error("Impossibile recuperare transazioni Enable Banking per {} dopo i retry: {}", accountUid, e.getMessage());
        return new ArrayList<>();
    }

    /**
     * BankReauthRequiredException non è tra le retryFor del metodo, ma essendoci un @Recover
     * sullo stesso metodo Spring Retry tenta comunque il recovery a fine tentativi: senza questa firma
     * fallirebbe con "Cannot locate recovery method" mascherando l'eccezione originale (stesso motivo
     * documentato in GocardlessService per GocardlessRequisitionExpiredException). Qui la rilanciamo
     * inalterata così AccountService può gestirla esplicitamente.
     */
    @Recover
    public List<EnableBankingTransaction> recoverGetTransactionsReauth(BankReauthRequiredException e, String accountUid, String dateFrom) {
        throw e;
    }
}
