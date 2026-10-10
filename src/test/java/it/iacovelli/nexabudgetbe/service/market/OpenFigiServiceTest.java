package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenFigiServiceTest {

    private static final String URL = "https://api.openfigi.com/v3/mapping";

    private static final String MAPPING = "[{\"data\":["
            + "{\"ticker\":\"VWCE\",\"exchCode\":\"GY\",\"name\":\"VANGUARD FTSE ALL-WORLD\",\"securityType\":\"ETP\"},"
            + "{\"ticker\":\"VWCE\",\"exchCode\":\"IM\",\"name\":\"VANGUARD FTSE ALL-WORLD\",\"securityType\":\"ETP\"},"
            + "{\"ticker\":\"VWCE\",\"exchCode\":\"GY\",\"name\":\"duplicato\",\"securityType\":\"ETP\"},"
            + "{\"ticker\":\"VWCE\",\"exchCode\":\"JT\",\"name\":\"borsa non supportata\",\"securityType\":\"ETP\"}]}]";

    @Test
    void searchByIsin_mapsExchangeCodesToYahooSuffixes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openfigi.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenFigiService figi = new OpenFigiService(builder.build(), "");
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("X-OPENFIGI-APIKEY"))
                .andExpect(jsonPath("$[0].idType").value("ID_ISIN"))
                .andExpect(jsonPath("$[0].idValue").value("IE00BK5BQT80"))
                .andRespond(withSuccess(MAPPING, MediaType.APPLICATION_JSON));

        List<InstrumentSearchResult> results = figi.searchByIsin("ie00bk5bqt80");

        assertEquals(List.of("VWCE.DE", "VWCE.MI"), results.stream().map(InstrumentSearchResult::getSymbol).toList());
        assertEquals(InvestmentAssetType.ETF, results.get(0).getSuggestedType());
        assertEquals("OPENFIGI", results.get(0).getProvider());
        server.verify();
    }

    @Test
    void searchByIsin_sendsApiKeyWhenConfigured() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openfigi.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenFigiService figi = new OpenFigiService(builder.build(), "secret-key");
        server.expect(once(), requestTo(URL))
                .andExpect(header("X-OPENFIGI-APIKEY", "secret-key"))
                .andRespond(withSuccess("[{\"warning\":\"No identifier found.\"}]", MediaType.APPLICATION_JSON));

        assertTrue(figi.searchByIsin("IE00BK5BQT80").isEmpty());
        server.verify();
    }

    @Test
    void searchByIsin_usClassShares_useDashNotSlash() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openfigi.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenFigiService figi = new OpenFigiService(builder.build(), "");
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(
                "[{\"data\":[{\"ticker\":\"BRK/B\",\"exchCode\":\"US\",\"name\":\"BERKSHIRE\",\"securityType\":\"Common Stock\"}]}]",
                MediaType.APPLICATION_JSON));

        List<InstrumentSearchResult> results = figi.searchByIsin("US0846707026");

        assertEquals("BRK-B", results.get(0).getSymbol());
        assertEquals(InvestmentAssetType.STOCK, results.get(0).getSuggestedType());
    }

    @Test
    void searchByIsin_notAnIsin_doesNotCallApi() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openfigi.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenFigiService figi = new OpenFigiService(builder.build(), "");

        assertTrue(figi.searchByIsin("apple").isEmpty());
        assertTrue(figi.searchByIsin("IE00BK5BQT8\"}]").isEmpty());
        server.verify();
    }

    @Test
    void searchByIsin_apiError_returnsEmpty() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openfigi.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenFigiService figi = new OpenFigiService(builder.build(), "");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertTrue(figi.searchByIsin("IE00BK5BQT80").isEmpty());
    }

    @Test
    void searchByIsin_emptyResults_areAnArrayList_notAnImmutableList() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openfigi.com");
        OpenFigiService figi = new OpenFigiService(builder.build(), "");

        // Vedi YahooFinanceServiceTest: in nativo SpEL non gestisce isEmpty() su List.of()
        assertEquals(java.util.ArrayList.class, figi.searchByIsin("not an isin").getClass());
    }

    @Test
    void isIsin() {
        assertTrue(OpenFigiService.isIsin("IT0005556011"));
        assertTrue(OpenFigiService.isIsin(" ie00bk5bqt80 "));
        assertFalse(OpenFigiService.isIsin("VWCE.DE"));
        assertFalse(OpenFigiService.isIsin(null));
    }
}
