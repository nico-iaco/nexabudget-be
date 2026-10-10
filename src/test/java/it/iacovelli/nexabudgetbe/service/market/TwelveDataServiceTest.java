package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.PriceSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwelveDataServiceTest {

    private static final String BASE = "https://api.twelvedata.com";

    @Test
    void withoutApiKey_isNotConfiguredAndDoesNotCall() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TwelveDataService twelve = new TwelveDataService(builder.build(), " ");

        assertFalse(twelve.isConfigured());
        assertTrue(twelve.getQuote("AAPL").isEmpty());
        server.verify();
    }

    @Test
    void getQuote_parsesCloseAndCurrency() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TwelveDataService twelve = new TwelveDataService(builder.build(), "key");
        server.expect(once(), requestTo(containsString("/quote?symbol=AAPL&apikey=key")))
                .andRespond(withSuccess("{\"symbol\":\"AAPL\",\"close\":\"231.50\",\"currency\":\"USD\"}", MediaType.APPLICATION_JSON));

        MarketQuote quote = twelve.getQuote("aapl").orElseThrow();

        assertEquals(0, new BigDecimal("231.50").compareTo(quote.getPrice()));
        assertEquals("USD", quote.getCurrency());
        assertEquals(PriceSource.TWELVE_DATA, quote.getSource());
    }

    @Test
    void getQuote_symbolWithExchangeSuffix_isSkipped() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TwelveDataService twelve = new TwelveDataService(builder.build(), "key");

        // Il free tier non copre XETRA: non si spende una chiamata dal limite giornaliero
        assertTrue(twelve.getQuote("VWCE.DE").isEmpty());
        server.verify();
    }

    @Test
    void getQuote_errorPayload_returnsEmpty() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TwelveDataService twelve = new TwelveDataService(builder.build(), "key");
        server.expect(once(), requestTo(containsString("/quote")))
                .andRespond(withSuccess("{\"code\":429,\"message\":\"limit\",\"status\":\"error\"}", MediaType.APPLICATION_JSON));

        assertTrue(twelve.getQuote("AAPL").isEmpty());
    }
}
