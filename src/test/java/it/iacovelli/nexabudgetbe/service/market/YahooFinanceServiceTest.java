package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import it.iacovelli.nexabudgetbe.model.PriceSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooFinanceServiceTest {

    private static final String BASE = "https://query1.finance.yahoo.com";

    private MockRestServiceServer server;
    private YahooFinanceService yahoo;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        yahoo = new YahooFinanceService(builder.build(), true);
    }

    private static String chart(String currency, String price, long time) {
        return "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"" + currency + "\",\"symbol\":\"X\","
                + "\"regularMarketPrice\":" + price + ",\"regularMarketTime\":" + time + "}}],\"error\":null}}";
    }

    @Test
    void getQuote_parsesPriceAndCurrency() {
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/VWCE.DE")))
                .andRespond(withSuccess(chart("EUR", "172.86", 1791560152L), MediaType.APPLICATION_JSON));

        Optional<MarketQuote> quote = yahoo.getQuote("vwce.de");

        assertTrue(quote.isPresent());
        assertEquals(0, new BigDecimal("172.86").compareTo(quote.get().getPrice()));
        assertEquals("EUR", quote.get().getCurrency());
        assertEquals("VWCE.DE", quote.get().getSymbol());
        assertEquals(PriceSource.YAHOO, quote.get().getSource());
        server.verify();
    }

    @Test
    void getQuote_pence_areConvertedToPounds() {
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/VWRL.L")))
                .andRespond(withSuccess(chart("GBp", "12345", 1791560152L), MediaType.APPLICATION_JSON));

        MarketQuote quote = yahoo.getQuote("VWRL.L").orElseThrow();

        // 12345 pence = 123,45 sterline: senza normalizzare il valore sarebbe 100 volte più grande
        assertEquals(0, new BigDecimal("123.45").compareTo(quote.getPrice()));
        assertEquals("GBP", quote.getCurrency());
    }

    @Test
    void getQuote_rateLimit429_returnsEmpty() {
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/AAPL")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertTrue(yahoo.getQuote("AAPL").isEmpty());
    }

    @Test
    void afterRateLimit_yahooIsNotCalledAgainDuringCooldown() {
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/AAPL")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertTrue(yahoo.getQuote("AAPL").isEmpty());
        // Nessuna altra expectation: una seconda chiamata HTTP (anche per altri simboli o per la ricerca) farebbe fallire il test
        assertTrue(yahoo.getQuote("VWCE.DE").isEmpty());
        assertTrue(yahoo.search("apple").isEmpty());
        server.verify();
    }

    @Test
    void searchRateLimit_alsoSuspendsQuotes() {
        server.expect(once(), requestTo(startsWith(BASE + "/v1/finance/search")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertTrue(yahoo.search("apple").isEmpty());
        assertTrue(yahoo.getQuote("AAPL").isEmpty());
        server.verify();
    }

    @Test
    void whenCooldownIsOver_yahooIsCalledAgain() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        YahooFinanceService noCooldown = new YahooFinanceService(builder.build(), true, Duration.ZERO);
        s.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/AAPL")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        s.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/AAPL")))
                .andRespond(withSuccess(chart("USD", "231.5", 1791560152L), MediaType.APPLICATION_JSON));

        assertTrue(noCooldown.getQuote("AAPL").isEmpty());
        assertTrue(noCooldown.getQuote("AAPL").isPresent());
        s.verify();
    }

    @Test
    void notFound_doesNotSuspendYahoo() {
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/NOPE")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/AAPL")))
                .andRespond(withSuccess(chart("USD", "231.5", 1791560152L), MediaType.APPLICATION_JSON));

        assertTrue(yahoo.getQuote("NOPE").isEmpty());
        assertTrue(yahoo.getQuote("AAPL").isPresent(), "un simbolo sconosciuto non è un rate limit");
        server.verify();
    }

    @Test
    void getQuote_unknownSymbolResponse_returnsEmpty() {
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/NOPE")))
                .andRespond(withSuccess("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found\"}}}",
                        MediaType.APPLICATION_JSON));

        assertTrue(yahoo.getQuote("NOPE").isEmpty());
    }

    @Test
    void getQuote_missingPrice_returnsEmpty() {
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/AAA")))
                .andRespond(withSuccess("{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"EUR\"}}]}}", MediaType.APPLICATION_JSON));

        assertTrue(yahoo.getQuote("AAA").isEmpty());
    }

    @Test
    void getQuote_fundWithoutLivePrice_usesLastClose() {
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"EUR\"},"
                + "\"indicators\":{\"quote\":[{\"close\":[10.5,null,11.25,null]}]}}]}}";
        server.expect(once(), requestTo(startsWith(BASE + "/v8/finance/chart/FUND1")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertEquals(0, new BigDecimal("11.25").compareTo(yahoo.getQuote("FUND1").orElseThrow().getPrice()));
    }

    @Test
    void getQuote_invalidSymbol_doesNotCallYahoo() {
        // Nessuna expectation registrata: una chiamata HTTP farebbe fallire il test
        assertTrue(yahoo.getQuote("../etc/passwd").isEmpty());
        assertTrue(yahoo.getQuote("A B").isEmpty());
        assertTrue(yahoo.getQuote("").isEmpty());
        server.verify();
    }

    @Test
    void disabled_returnsEmptyWithoutCalling() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        YahooFinanceService disabled = new YahooFinanceService(builder.build(), false);

        assertFalse(disabled.isConfigured());
        assertTrue(disabled.getQuote("AAPL").isEmpty());
        assertTrue(disabled.search("apple").isEmpty());
        s.verify();
    }

    @Test
    void search_mapsQuoteTypes() {
        String body = "{\"quotes\":["
                + "{\"symbol\":\"VWRA.L\",\"longname\":\"Vanguard FTSE All-World UCITS ETF\",\"exchDisp\":\"London\",\"quoteType\":\"ETF\"},"
                + "{\"symbol\":\"ENEL.MI\",\"shortname\":\"ENEL\",\"exchDisp\":\"Milan\",\"quoteType\":\"EQUITY\"},"
                + "{\"symbol\":\"BAD SYMBOL\",\"shortname\":\"x\",\"quoteType\":\"ETF\"}]}";
        server.expect(once(), requestTo(startsWith(BASE + "/v1/finance/search")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<InstrumentSearchResult> results = yahoo.search("world");

        assertEquals(2, results.size(), "il simbolo non valido va scartato");
        assertEquals("VWRA.L", results.get(0).getSymbol());
        assertEquals(InvestmentAssetType.ETF, results.get(0).getSuggestedType());
        assertEquals("Vanguard FTSE All-World UCITS ETF", results.get(0).getName());
        assertEquals(InvestmentAssetType.STOCK, results.get(1).getSuggestedType());
        assertEquals("ENEL", results.get(1).getName());
    }

    @Test
    void search_emptyResults_areAnArrayList_notAnImmutableList() {
        // Nell'immagine nativa la condizione di cache "#result.isEmpty()" (SpEL) fallisce su List.of() con un 500,
        // mentre sulla JVM funziona: il test fissa il vincolo che altrimenti emergerebbe solo in produzione
        server.expect(once(), requestTo(startsWith(BASE + "/v1/finance/search")))
                .andRespond(withSuccess("{\"quotes\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(java.util.ArrayList.class, yahoo.search("nothing").getClass());

        assertEquals(java.util.ArrayList.class, yahoo.search("  ").getClass(), "query vuota");
        YahooFinanceService disabled = new YahooFinanceService(RestClient.builder().baseUrl(BASE).build(), false);
        assertEquals(java.util.ArrayList.class, disabled.search("apple").getClass(), "provider disattivato");
    }

    @Test
    void search_serverError_returnsEmpty() {
        server.expect(once(), requestTo(startsWith(BASE + "/v1/finance/search")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertTrue(yahoo.search("apple").isEmpty());
    }
}
