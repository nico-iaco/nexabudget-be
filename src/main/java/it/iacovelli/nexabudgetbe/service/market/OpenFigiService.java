package it.iacovelli.nexabudgetbe.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mappatura ISIN → ticker tramite OpenFIGI (gratuito: 25 richieste/minuto senza chiave, di più con
 * {@code OPENFIGI_API_KEY}). Yahoo non trova tutti i listing di un ISIN: OpenFIGI li elenca per borsa e qui si
 * convertono nella notazione Yahoo (suffisso di borsa). I risultati non sono verificati: la quotazione si
 * controlla alla creazione dell'asset.
 */
@Service
public class OpenFigiService {

    private static final Logger logger = LoggerFactory.getLogger(OpenFigiService.class);
    private static final String BASE_URL = "https://api.openfigi.com";
    static final Pattern ISIN = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");

    // exchCode OpenFIGI → suffisso Yahoo ("" = nessun suffisso, listing USA)
    private static final Map<String, String> YAHOO_SUFFIX = Map.ofEntries(
            Map.entry("US", ""), Map.entry("UN", ""), Map.entry("UQ", ""), Map.entry("UA", ""),
            Map.entry("UP", ""), Map.entry("UR", ""), Map.entry("UW", ""),
            Map.entry("GY", ".DE"), Map.entry("GF", ".F"), Map.entry("IM", ".MI"), Map.entry("NA", ".AS"),
            Map.entry("FP", ".PA"), Map.entry("LN", ".L"), Map.entry("SW", ".SW"), Map.entry("SM", ".MC"),
            Map.entry("AV", ".VI"), Map.entry("BB", ".BR"), Map.entry("PL", ".LS"), Map.entry("ID", ".IR"),
            Map.entry("FH", ".HE"), Map.entry("SS", ".ST"), Map.entry("DC", ".CO"), Map.entry("NO", ".OL"));

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;

    @Autowired
    public OpenFigiService(@Value("${nexabudget.market.openfigi.api-key:}") String apiKey) {
        this(buildRestClient(), apiKey);
    }

    // Per i test
    OpenFigiService(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    public static boolean isIsin(String value) {
        return value != null && ISIN.matcher(value.trim().toUpperCase()).matches();
    }

    // unless: la lista vuota (ISIN sconosciuto o errore) non va cachata.
    // Si restituisce sempre una ArrayList: nell'immagine nativa SpEL non trova isEmpty() sulle liste immutabili del JDK
    // (vedi YahooFinanceService.search)
    @Cacheable(value = CacheConfig.MARKET_SEARCH_CACHE, key = "'FIGI:' + #isin.trim().toUpperCase()",
            unless = "#result == null || #result.isEmpty()")
    public List<InstrumentSearchResult> searchByIsin(String isin) {
        if (!isIsin(isin)) {
            return new ArrayList<>();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/v3/mapping")
                    .contentType(MediaType.APPLICATION_JSON);
            if (!apiKey.isEmpty()) {
                request = request.header("X-OPENFIGI-APIKEY", apiKey);
            }
            String body = request
                    .body("[{\"idType\":\"ID_ISIN\",\"idValue\":\"" + isin.trim().toUpperCase() + "\"}]")
                    .retrieve()
                    .body(String.class);
            return parseMapping(body);
        } catch (RestClientException e) {
            logger.warn("Mapping OpenFIGI di {} non disponibile: {}", isin, e.getMessage());
            return new ArrayList<>();
        }
    }

    List<InstrumentSearchResult> parseMapping(String body) {
        List<InstrumentSearchResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode job : root) {
                for (JsonNode item : job.path("data")) {
                    String suffix = YAHOO_SUFFIX.get(item.path("exchCode").asText(""));
                    String ticker = item.path("ticker").asText("");
                    if (suffix == null || ticker.isBlank()) {
                        continue;
                    }
                    // FIGI scrive le classi di azioni con la barra (BRK/B), Yahoo con il trattino
                    String symbol = ticker.replace('/', '-').toUpperCase() + suffix;
                    if (!YahooFinanceService.isValidSymbol(symbol) || !seen.add(symbol)) {
                        continue;
                    }
                    results.add(InstrumentSearchResult.builder()
                            .symbol(symbol)
                            .name(item.path("name").asText(ticker))
                            .exchange(item.path("exchCode").asText(""))
                            .suggestedType(mapSecurityType(item.path("securityType").asText("")))
                            .provider("OPENFIGI")
                            .build());
                }
            }
        } catch (Exception e) {
            logger.error("Errore parsing risposta OpenFIGI: {}", e.getMessage());
        }
        return results;
    }

    static InvestmentAssetType mapSecurityType(String securityType) {
        String type = securityType.toUpperCase();
        if (type.contains("ETP") || type.contains("ETF")) {
            return InvestmentAssetType.ETF;
        }
        if (type.contains("COMMON STOCK") || type.contains("DEPOSITARY RECEIPT")) {
            return InvestmentAssetType.STOCK;
        }
        if (type.contains("FUND") || type.contains("OPEN-END")) {
            return InvestmentAssetType.FUND;
        }
        return InvestmentAssetType.OTHER;
    }
}
