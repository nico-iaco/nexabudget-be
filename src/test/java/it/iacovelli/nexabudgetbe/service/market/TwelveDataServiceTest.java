package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.PriceSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwelveDataServiceTest {

    private static final String BASE = "https://api.twelvedata.com";
    private static final String PLAN_ERROR = "{\"code\":403,\"message\":\"This symbol is available starting with the Grow plan. "
            + "Consider upgrading your API Key now\",\"status\":\"error\"}";

    private MockRestServiceServer server;

    private TwelveDataService service(String apiKey, Duration unsupportedTtl, Duration rateLimitCooldown) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        return new TwelveDataService(builder.build(), apiKey, unsupportedTtl, rateLimitCooldown);
    }

    private TwelveDataService service() {
        return service("key", Duration.ofDays(1), Duration.ofMinutes(1));
    }

    private static String quote(String close, String currency) {
        return "{\"symbol\":\"X\",\"close\":\"" + close + "\",\"currency\":\"" + currency + "\"}";
    }

    @Test
    void withoutApiKey_isNotConfiguredAndDoesNotCall() {
        TwelveDataService twelve = service(" ", Duration.ofDays(1), Duration.ofMinutes(1));

        assertFalse(twelve.isConfigured());
        assertTrue(twelve.getQuote("AAPL").isEmpty());
        server.verify();
    }

    @Test
    void usSymbol_isQueriedWithoutExchange() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(BASE + "/quote?symbol=AAPL&apikey=key"))
                .andRespond(withSuccess(quote("231.50", "USD"), MediaType.APPLICATION_JSON));

        MarketQuote quote = twelve.getQuote("aapl").orElseThrow();

        assertEquals(0, new BigDecimal("231.50").compareTo(quote.getPrice()));
        assertEquals("USD", quote.getCurrency());
        assertEquals(PriceSource.TWELVE_DATA, quote.getSource());
        server.verify();
    }

    @Test
    void usShareClass_dashBecomesDot() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(BASE + "/quote?symbol=BRK.B&apikey=key"))
                .andRespond(withSuccess(quote("480", "USD"), MediaType.APPLICATION_JSON));

        assertTrue(twelve.getQuote("BRK-B").isPresent());
        server.verify();
    }

    @Test
    void europeanSymbols_areQueriedWithTheirExchange() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(BASE + "/quote?symbol=VWCE&mic_code=XMIL&apikey=key"))
                .andRespond(withSuccess(quote("172.86", "EUR"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/quote?symbol=VWCE&mic_code=XETR&apikey=key"))
                .andRespond(withSuccess(quote("172.90", "EUR"), MediaType.APPLICATION_JSON));

        MarketQuote milan = twelve.getQuote("VWCE.MI").orElseThrow();
        MarketQuote xetra = twelve.getQuote("VWCE.DE").orElseThrow();

        assertEquals("VWCE.MI", milan.getSymbol(), "il simbolo restituito resta quello Yahoo dell'asset");
        assertEquals("EUR", milan.getCurrency());
        assertEquals(0, new BigDecimal("172.90").compareTo(xetra.getPrice()));
        server.verify();
    }

    @Test
    void quoteTime_comesFromTheResponse_notFromTheDownload() {
        TwelveDataService twelve = service();
        long marketTime = 1791835140L;
        server.expect(once(), requestTo(containsString("/quote")))
                .andRespond(withSuccess("{\"close\":\"10\",\"currency\":\"EUR\",\"last_quote_at\":" + marketTime + "}", MediaType.APPLICATION_JSON));

        LocalDateTime asOf = twelve.getQuote("VWCE.MI").orElseThrow().getAsOf();

        // Con la cache di un giorno priceAsOf deve dire quando il mercato ha quotato, non quando l'abbiamo scaricato
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochSecond(marketTime), ZoneId.systemDefault()), asOf);
    }

    @Test
    void londonPence_areConvertedToPounds() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(containsString("mic_code=XLON")))
                .andRespond(withSuccess(quote("12345", "GBX"), MediaType.APPLICATION_JSON));

        MarketQuote quote = twelve.getQuote("VWRL.L").orElseThrow();

        assertEquals(0, new BigDecimal("123.45").compareTo(quote.getPrice()));
        assertEquals("GBP", quote.getCurrency());
    }

    @Test
    void unmappedExchangeSuffix_isSkippedWithoutCalling() {
        TwelveDataService twelve = service();

        assertTrue(twelve.getQuote("ABC.XX").isEmpty());
        server.verify();
    }

    @Test
    void planRestriction_isNotRetriedForTheSameSymbol_butOthersStillWork() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(containsString("symbol=VWCE&mic_code=XMIL")))
                .andRespond(withSuccess(PLAN_ERROR, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("symbol=AAPL")))
                .andRespond(withSuccess(quote("231.5", "USD"), MediaType.APPLICATION_JSON));

        assertTrue(twelve.getQuote("VWCE.MI").isEmpty());
        // Seconda richiesta per lo stesso simbolo: nessuna chiamata (una seconda expectation non esiste)
        assertTrue(twelve.getQuote("VWCE.MI").isEmpty());
        assertTrue(twelve.getQuote("AAPL").isPresent(), "un simbolo non coperto non blocca gli altri");
        server.verify();
    }

    @Test
    void planRestrictionAsHttpError_isHandledTheSameWay() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(containsString("mic_code=XMIL")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(PLAN_ERROR));

        assertTrue(twelve.getQuote("VWCE.MI").isEmpty());
        assertTrue(twelve.getQuote("VWCE.MI").isEmpty());
        server.verify();
    }

    @Test
    void planRestriction_isRetriedAfterTheTtl() {
        TwelveDataService twelve = service("key", Duration.ZERO, Duration.ofMinutes(1));
        server.expect(once(), requestTo(containsString("mic_code=XMIL"))).andRespond(withSuccess(PLAN_ERROR, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("mic_code=XMIL")))
                .andRespond(withSuccess(quote("172.86", "EUR"), MediaType.APPLICATION_JSON));

        assertTrue(twelve.getQuote("VWCE.MI").isEmpty());
        assertTrue(twelve.getQuote("VWCE.MI").isPresent(), "dopo il TTL il simbolo si riprova (es. piano cambiato)");
        server.verify();
    }

    @Test
    void rateLimit_suspendsAllCallsDuringCooldown() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(containsString("symbol=AAPL")))
                .andRespond(withSuccess("{\"code\":429,\"message\":\"You have run out of API credits for the current minute\",\"status\":\"error\"}",
                        MediaType.APPLICATION_JSON));

        assertTrue(twelve.getQuote("AAPL").isEmpty());
        assertTrue(twelve.getQuote("MSFT").isEmpty(), "nessuna chiamata durante il cooldown");
        server.verify();
    }

    @Test
    void rateLimit_isRetriedWhenTheCooldownIsOver() {
        TwelveDataService twelve = service("key", Duration.ofDays(1), Duration.ZERO);
        server.expect(once(), requestTo(containsString("symbol=AAPL")))
                .andRespond(withSuccess("{\"code\":429,\"message\":\"limit\",\"status\":\"error\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("symbol=AAPL")))
                .andRespond(withSuccess(quote("231.5", "USD"), MediaType.APPLICATION_JSON));

        assertTrue(twelve.getQuote("AAPL").isEmpty());
        assertTrue(twelve.getQuote("AAPL").isPresent());
        server.verify();
    }

    @Test
    void invalidKey_isNotSuppressed_soFixingTheKeyWorksWithoutWaiting() {
        TwelveDataService twelve = service();
        String unauthorized = "{\"code\":401,\"message\":\"apikey parameter is incorrect\",\"status\":\"error\"}";
        server.expect(once(), requestTo(containsString("symbol=AAPL"))).andRespond(withSuccess(unauthorized, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(containsString("symbol=AAPL")))
                .andRespond(withSuccess(quote("231.5", "USD"), MediaType.APPLICATION_JSON));

        assertTrue(twelve.getQuote("AAPL").isEmpty());
        assertTrue(twelve.getQuote("AAPL").isPresent());
        server.verify();
    }

    @Test
    void serverError_returnsEmpty() {
        TwelveDataService twelve = service();
        server.expect(once(), requestTo(containsString("/quote"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertTrue(twelve.getQuote("AAPL").isEmpty());
    }

    @Test
    void toTarget_mapsYahooSuffixesToExchanges() {
        assertEquals(Optional.of(new TwelveDataService.Target("VWCE", "XMIL")), TwelveDataService.toTarget("VWCE.MI"));
        assertEquals(Optional.of(new TwelveDataService.Target("SAP", "XETR")), TwelveDataService.toTarget("sap.de"));
        assertEquals(Optional.of(new TwelveDataService.Target("AAPL", null)), TwelveDataService.toTarget("AAPL"));
        assertEquals(Optional.empty(), TwelveDataService.toTarget("X.ZZ"));
        assertThat(TwelveDataService.toTarget("ENEL.MI").orElseThrow().micCode(), not(containsString("XETR")));
    }

    private static void assertThat(String actual, org.hamcrest.Matcher<String> matcher) {
        org.hamcrest.MatcherAssert.assertThat(actual, matcher);
    }
}
