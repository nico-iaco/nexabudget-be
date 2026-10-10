package it.iacovelli.nexabudgetbe.service.market;

import it.iacovelli.nexabudgetbe.model.InvestmentAsset;
import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import it.iacovelli.nexabudgetbe.model.PriceSource;
import it.iacovelli.nexabudgetbe.repository.InvestmentAssetRepository;
import it.iacovelli.nexabudgetbe.service.market.MarketPriceService.ResolvedPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MarketPriceServiceTest {

    private final YahooFinanceService yahoo = mock(YahooFinanceService.class);
    private final TwelveDataService twelveData = mock(TwelveDataService.class);
    private final OpenFigiService openFigi = mock(OpenFigiService.class);
    private final InvestmentAssetRepository repository = mock(InvestmentAssetRepository.class);
    private MarketPriceService service;

    @BeforeEach
    void setUp() {
        when(yahoo.isConfigured()).thenReturn(true);
        when(twelveData.isConfigured()).thenReturn(true);
        service = new MarketPriceService(yahoo, twelveData, openFigi, repository);
    }

    private InvestmentAsset asset(PriceSource source) {
        return InvestmentAsset.builder().id(UUID.randomUUID()).assetType(InvestmentAssetType.ETF).name("VWCE")
                .symbol("VWCE.DE").currency("EUR").priceSource(source).build();
    }

    private static MarketQuote quote(PriceSource source, String price) {
        return MarketQuote.builder().symbol("VWCE.DE").price(new BigDecimal(price)).currency("EUR")
                .asOf(LocalDateTime.of(2026, 10, 9, 17, 30)).source(source).build();
    }

    @Test
    void liveQuote_isUsedAndPersistedAsLastPrice() {
        InvestmentAsset a = asset(PriceSource.YAHOO);
        when(yahoo.getQuote("VWCE.DE")).thenReturn(Optional.of(quote(PriceSource.YAHOO, "172.86")));

        ResolvedPrice price = service.resolve(a).orElseThrow();

        assertEquals(0, new BigDecimal("172.86").compareTo(price.price()));
        assertEquals(PriceSource.YAHOO, price.source());
        assertFalse(price.stale());
        verify(repository).updateLastPrice(eq(a.getId()), eq(new BigDecimal("172.86")), eq("EUR"), any());
        verifyNoInteractions(openFigi);
    }

    @Test
    void yahooDown_fallsBackToTwelveData() {
        InvestmentAsset a = asset(PriceSource.YAHOO);
        when(yahoo.getQuote("VWCE.DE")).thenReturn(Optional.empty());
        when(twelveData.getQuote("VWCE.DE")).thenReturn(Optional.of(quote(PriceSource.TWELVE_DATA, "171.00")));

        ResolvedPrice price = service.resolve(a).orElseThrow();

        assertEquals(PriceSource.TWELVE_DATA, price.source());
        assertFalse(price.stale());
    }

    @Test
    void preferredTwelveData_isTriedFirst() {
        InvestmentAsset a = asset(PriceSource.TWELVE_DATA);
        when(twelveData.getQuote("VWCE.DE")).thenReturn(Optional.of(quote(PriceSource.TWELVE_DATA, "171.00")));

        service.resolve(a);

        verify(yahoo, never()).getQuote(anyString());
    }

    @Test
    void unconfiguredProvider_isNotCalled() {
        when(twelveData.isConfigured()).thenReturn(false);
        InvestmentAsset a = asset(PriceSource.YAHOO);
        when(yahoo.getQuote("VWCE.DE")).thenReturn(Optional.empty());

        assertTrue(service.resolve(a).isEmpty());
        verify(twelveData, never()).getQuote(anyString());
    }

    @Test
    void allProvidersDown_usesLastKnownPriceMarkedStale() {
        InvestmentAsset a = asset(PriceSource.YAHOO);
        a.setLastPrice(new BigDecimal("168.40"));
        a.setLastPriceCurrency("EUR");
        a.setLastPriceAt(LocalDateTime.of(2026, 10, 8, 17, 30));

        ResolvedPrice price = service.resolve(a).orElseThrow();

        assertEquals(0, new BigDecimal("168.40").compareTo(price.price()));
        assertTrue(price.stale());
        verify(repository, never()).updateLastPrice(any(), any(), any(), any());
    }

    @Test
    void noLivePriceNoLastPrice_usesManualPriceMarkedStale() {
        InvestmentAsset a = asset(PriceSource.YAHOO);
        a.setManualPrice(new BigDecimal("160"));
        a.setManualPriceAt(LocalDateTime.of(2026, 9, 1, 10, 0));

        ResolvedPrice price = service.resolve(a).orElseThrow();

        assertEquals(PriceSource.MANUAL, price.source());
        assertTrue(price.stale());
    }

    @Test
    void manualSource_neverCallsProviders() {
        InvestmentAsset a = asset(PriceSource.MANUAL);
        a.setManualPrice(new BigDecimal("101.35"));
        a.setManualPriceAt(LocalDateTime.of(2026, 10, 1, 9, 0));

        ResolvedPrice price = service.resolve(a).orElseThrow();

        assertEquals(0, new BigDecimal("101.35").compareTo(price.price()));
        assertEquals(PriceSource.MANUAL, price.source());
        assertFalse(price.stale());
        verify(yahoo, never()).getQuote(anyString());
        verify(twelveData, never()).getQuote(anyString());
    }

    @Test
    void manualSourceWithoutPrice_hasNoPrice() {
        assertTrue(service.resolve(asset(PriceSource.MANUAL)).isEmpty());
    }

    @Test
    void alreadyPersistedQuote_doesNotWriteAgain() {
        InvestmentAsset a = asset(PriceSource.YAHOO);
        MarketQuote q = quote(PriceSource.YAHOO, "172.86");
        a.setLastPrice(q.getPrice());
        a.setLastPriceAt(q.getAsOf());
        when(yahoo.getQuote("VWCE.DE")).thenReturn(Optional.of(q));

        service.resolve(a);

        verify(repository, never()).updateLastPrice(any(), any(), any(), any());
    }

    @Test
    void persistFailure_doesNotFailResolution() {
        InvestmentAsset a = asset(PriceSource.YAHOO);
        when(yahoo.getQuote("VWCE.DE")).thenReturn(Optional.of(quote(PriceSource.YAHOO, "172.86")));
        when(repository.updateLastPrice(any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));

        assertTrue(service.resolve(a).isPresent());
    }

    @Test
    void resolveAll_manyAssets_eachGetsItsOwnPrice() {
        InvestmentAsset a = asset(PriceSource.YAHOO);
        InvestmentAsset b = asset(PriceSource.YAHOO);
        b.setSymbol("ENEL.MI");
        when(yahoo.getQuote("VWCE.DE")).thenReturn(Optional.of(quote(PriceSource.YAHOO, "172.86")));
        when(yahoo.getQuote("ENEL.MI")).thenReturn(Optional.of(
                MarketQuote.builder().symbol("ENEL.MI").price(new BigDecimal("7.10")).currency("EUR")
                        .asOf(LocalDateTime.now()).source(PriceSource.YAHOO).build()));

        Map<UUID, ResolvedPrice> prices = service.resolveAll(List.of(a, b));

        assertEquals(0, new BigDecimal("172.86").compareTo(prices.get(a.getId()).price()));
        assertEquals(0, new BigDecimal("7.10").compareTo(prices.get(b.getId()).price()));
    }

    @Test
    void search_isinWithoutYahooResults_fallsBackToOpenFigi() {
        InstrumentSearchResult figiResult = InstrumentSearchResult.builder().symbol("VWCE.DE").provider("OPENFIGI").build();
        when(yahoo.search("IE00BK5BQT80")).thenReturn(List.of());
        when(openFigi.searchByIsin("IE00BK5BQT80")).thenReturn(List.of(figiResult));

        assertEquals(List.of(figiResult), service.search(" IE00BK5BQT80 "));
    }

    @Test
    void search_plainTextWithoutResults_doesNotCallOpenFigi() {
        when(yahoo.search("zzz")).thenReturn(List.of());

        assertTrue(service.search("zzz").isEmpty());
        verifyNoInteractions(openFigi);
    }

    @Test
    void search_yahooResultsPresent_doesNotCallOpenFigi() {
        when(yahoo.search("IE00BK5BQT80")).thenReturn(List.of(InstrumentSearchResult.builder().symbol("VWRA.L").build()));

        assertEquals(1, service.search("IE00BK5BQT80").size());
        verifyNoInteractions(openFigi);
    }
}
