package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.dto.InvestmentDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.InvestmentOperationRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.market.MarketPriceService;
import it.iacovelli.nexabudgetbe.service.market.MarketPriceService.ResolvedPrice;
import it.iacovelli.nexabudgetbe.service.market.MarketQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class InvestmentPortfolioServiceTest {

    @Autowired
    private InvestmentPortfolioService service;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvestmentOperationRepository operationRepository;

    @MockitoBean
    private MarketPriceService marketPriceService;
    @MockitoBean
    private ExchangeRateService exchangeRateService;

    private User user;
    // Prezzi "di mercato" per simbolo, usati dal mock di MarketPriceService
    private final Map<String, ResolvedPrice> prices = new HashMap<>();
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        user = newUser("investor");
        when(marketPriceService.resolveAll(anyList())).thenAnswer(inv -> {
            Map<UUID, ResolvedPrice> result = new HashMap<>();
            for (InvestmentAsset a : inv.<List<InvestmentAsset>>getArgument(0)) {
                ResolvedPrice price = a.getSymbol() != null ? prices.get(a.getSymbol()) : null;
                if (price != null) {
                    result.put(a.getId(), price);
                }
            }
            return result;
        });
        when(exchangeRateService.getRate(anyString(), anyString())).thenAnswer(inv -> {
            if ("USD".equals(inv.getArgument(0)) && "EUR".equals(inv.getArgument(1))) {
                return Optional.of(new BigDecimal("0.9"));
            }
            return Optional.empty();
        });
    }

    private User newUser(String name) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@test.com");
        u.setPasswordHash("password");
        u.setDefaultCurrency("EUR");
        return userRepository.save(u);
    }

    private void price(String symbol, String price, String currency) {
        prices.put(symbol, new ResolvedPrice(new BigDecimal(price), currency, LocalDateTime.now(), PriceSource.YAHOO, false));
    }

    private InvestmentDto.AssetResponse etf(String symbol, String currency) {
        return service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.ETF).name("ETF " + symbol).symbol(symbol).currency(currency).build());
    }

    private InvestmentDto.OperationResponse buy(UUID assetId, LocalDate date, String qty, String price, String fees) {
        return service.addOperation(user, assetId, InvestmentDto.OperationRequest.builder()
                .type(InvestmentOperationType.BUY).operationDate(date)
                .quantity(new BigDecimal(qty)).price(new BigDecimal(price)).fees(new BigDecimal(fees)).build());
    }

    private InvestmentDto.OperationResponse sell(UUID assetId, LocalDate date, String qty, String price) {
        return service.addOperation(user, assetId, InvestmentDto.OperationRequest.builder()
                .type(InvestmentOperationType.SELL).operationDate(date)
                .quantity(new BigDecimal(qty)).price(new BigDecimal(price)).build());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual, "valore null, atteso " + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "atteso " + expected + " ma era " + actual);
    }

    // ─── Asset ──────────────────────────────────────────────────────────────────

    @Test
    void createAsset_normalizesIdentifiersAndDefaultsToYahoo() {
        InvestmentDto.AssetResponse asset = service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.ETF).name(" VWCE ").symbol("vwce.de").isin("ie00bk5bqt80").currency("eur").build());

        assertEquals("VWCE.DE", asset.getSymbol());
        assertEquals("IE00BK5BQT80", asset.getIsin());
        assertEquals("EUR", asset.getCurrency());
        assertEquals("VWCE", asset.getName());
        assertEquals(PriceSource.YAHOO, asset.getPriceSource());
    }

    @Test
    void createAsset_withoutCurrency_usesQuoteCurrency() {
        when(marketPriceService.getLiveQuote(anyString(), any())).thenReturn(Optional.of(
                MarketQuote.builder().symbol("VWCE.DE").price(BigDecimal.TEN).currency("EUR").build()));

        InvestmentDto.AssetResponse asset = service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.ETF).name("VWCE").symbol("VWCE.DE").build());

        assertEquals("EUR", asset.getCurrency());
    }

    @Test
    void createAsset_withoutCurrencyAndWithoutQuote_isRejected() {
        when(marketPriceService.getLiveQuote(anyString(), any())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.ETF).name("X").symbol("XYZ").build()));
        assertThrows(IllegalArgumentException.class, () -> service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.OTHER).name("Oro fisico").build()));
    }

    @Test
    void createAsset_withoutSymbol_isManual_andAutomaticPricingNeedsSymbol() {
        InvestmentDto.AssetResponse btp = service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.BOND).name("BTP 2029").isin("IT0005716839").currency("EUR")
                .manualPrice(new BigDecimal("101.35")).couponRate(new BigDecimal("3.5"))
                .couponFrequency(CouponFrequency.SEMIANNUAL).maturityDate(LocalDate.of(2029, 9, 1)).build());

        assertEquals(PriceSource.MANUAL, btp.getPriceSource());
        assertDecimal("101.35", btp.getManualPrice());
        assertDecimal("3.5", btp.getCouponRate());
        assertNotNull(btp.getManualPriceAt());

        assertThrows(IllegalArgumentException.class, () -> service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.ETF).name("X").currency("EUR").priceSource(PriceSource.YAHOO).build()));
    }

    @Test
    void createAsset_duplicateSymbolOrIsin_isConflict() {
        service.createAsset(user, InvestmentDto.AssetRequest.builder().assetType(InvestmentAssetType.ETF).name("A")
                .symbol("VWCE.DE").isin("IE00BK5BQT80").currency("EUR").build());

        assertThrows(IllegalStateException.class, () -> etf("VWCE.DE", "EUR"));
        assertThrows(IllegalStateException.class, () -> service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.ETF).name("B").symbol("OTHER").isin("ie00bk5bqt80").currency("EUR").build()));
    }

    @Test
    void couponFieldsAreIgnoredForNonBonds() {
        InvestmentDto.AssetResponse asset = service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.ETF).name("A").symbol("A.DE").currency("EUR")
                .couponRate(new BigDecimal("3")).maturityDate(LocalDate.of(2030, 1, 1)).build());

        assertNull(asset.getCouponRate());
        assertNull(asset.getMaturityDate());
    }

    @Test
    void otherUsersAsset_isNotFound() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        User intruder = newUser("intruder");

        assertThrows(ResponseStatusException.class, () -> service.getAsset(intruder, asset.getId()));
        assertThrows(ResponseStatusException.class, () -> service.deleteAsset(intruder, asset.getId()));
        assertThrows(ResponseStatusException.class, () -> service.addOperation(intruder, asset.getId(),
                InvestmentDto.OperationRequest.builder().type(InvestmentOperationType.BUY).operationDate(today)
                        .quantity(BigDecimal.ONE).price(BigDecimal.ONE).build()));
        assertTrue(service.getAssets(intruder).isEmpty());
    }

    @Test
    void updateAsset_symbolChange_resetsNothingVisibleButAppliesFields() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");

        InvestmentDto.AssetResponse updated = service.updateAsset(user, asset.getId(), InvestmentDto.AssetUpdateRequest.builder()
                .assetType(InvestmentAssetType.ETF).name("Vanguard").symbol("vwrl.l").priceSource(PriceSource.YAHOO).build());

        assertEquals("Vanguard", updated.getName());
        assertEquals("VWRL.L", updated.getSymbol());
        assertEquals("EUR", updated.getCurrency(), "la valuta non si modifica");
    }

    @Test
    void updateAsset_bondToEtfWithOperations_isConflict() {
        InvestmentDto.AssetResponse btp = service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.BOND).name("BTP").currency("EUR").build());
        buy(btp.getId(), today.minusDays(5), "10000", "100", "0");

        assertThrows(IllegalStateException.class, () -> service.updateAsset(user, btp.getId(),
                InvestmentDto.AssetUpdateRequest.builder().assetType(InvestmentAssetType.ETF).name("BTP")
                        .priceSource(PriceSource.MANUAL).build()));
    }

    @Test
    void deleteAsset_removesItsOperations() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(5), "10", "100", "0");

        service.deleteAsset(user, asset.getId());

        assertTrue(service.getAssets(user).isEmpty());
        assertTrue(operationRepository.findAllByUser(user).isEmpty());
    }

    // ─── Operazioni ─────────────────────────────────────────────────────────────

    @Test
    void operations_areValidatedByType() {
        UUID id = etf("VWCE.DE", "EUR").getId();

        assertThrows(IllegalArgumentException.class, () -> service.addOperation(user, id,
                InvestmentDto.OperationRequest.builder().type(InvestmentOperationType.BUY).operationDate(today)
                        .price(BigDecimal.TEN).build()), "BUY senza quantità");
        assertThrows(IllegalArgumentException.class, () -> service.addOperation(user, id,
                InvestmentDto.OperationRequest.builder().type(InvestmentOperationType.DIVIDEND).operationDate(today).build()),
                "DIVIDEND senza importo");
        assertThrows(IllegalArgumentException.class, () -> buy(id, today.plusDays(1), "1", "10", "0"), "data futura");
    }

    @Test
    void sellBeyondHeldQuantity_isConflict() {
        UUID id = etf("VWCE.DE", "EUR").getId();
        buy(id, today.minusDays(10), "5", "100", "0");

        assertThrows(IllegalStateException.class, () -> sell(id, today.minusDays(5), "6", "110"));
    }

    @Test
    void deletingBuyAlreadySold_isConflict() {
        UUID id = etf("VWCE.DE", "EUR").getId();
        InvestmentDto.OperationResponse buy = buy(id, today.minusDays(10), "5", "100", "0");
        sell(id, today.minusDays(5), "5", "110");

        assertThrows(IllegalStateException.class, () -> service.deleteOperation(user, buy.getId()));
    }

    @Test
    void shrinkingBuyBelowSoldQuantity_isConflict() {
        UUID id = etf("VWCE.DE", "EUR").getId();
        InvestmentDto.OperationResponse buy = buy(id, today.minusDays(10), "5", "100", "0");
        sell(id, today.minusDays(5), "5", "110");

        assertThrows(IllegalStateException.class, () -> service.updateOperation(user, buy.getId(),
                InvestmentDto.OperationRequest.builder().type(InvestmentOperationType.BUY).operationDate(today.minusDays(10))
                        .quantity(new BigDecimal("3")).price(new BigDecimal("100")).build()));
    }

    @Test
    void getOperations_newestFirst() {
        UUID id = etf("VWCE.DE", "EUR").getId();
        buy(id, today.minusDays(20), "1", "100", "0");
        buy(id, today.minusDays(5), "2", "100", "0");

        List<InvestmentDto.OperationResponse> ops = service.getOperations(user, id);

        assertEquals(2, ops.size());
        assertEquals(today.minusDays(5), ops.get(0).getOperationDate());
    }

    // ─── Portafoglio ────────────────────────────────────────────────────────────

    @Test
    void portfolio_valuesPositionAtMarketPrice() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(30), "10", "100", "5");
        price("VWCE.DE", "110", "EUR");

        InvestmentDto.PortfolioResponse portfolio = service.getPortfolio(user, null);

        assertEquals("EUR", portfolio.getCurrency());
        assertTrue(portfolio.isComplete());
        InvestmentDto.PositionResponse pos = portfolio.getPositions().get(0);
        assertDecimal("10", pos.getQuantity());
        assertDecimal("100.5", pos.getAvgPrice());
        assertDecimal("1005", pos.getCostBasis());
        assertDecimal("1100", pos.getMarketValue());
        assertDecimal("95", pos.getUnrealizedPl());
        assertDecimal("9.45", pos.getUnrealizedPlPercent());
        assertDecimal("1100", portfolio.getTotalValue());
        assertDecimal("1005", portfolio.getTotalCostBasis());
        assertDecimal("95", portfolio.getUnrealizedPl());
        assertEquals("ETF", portfolio.getAllocationByType().get(0).getKey());
        assertDecimal("100", portfolio.getAllocationByType().get(0).getPercent());
    }

    @Test
    void portfolio_foreignCurrencyAsset_isConvertedAtCurrentRate() {
        InvestmentDto.AssetResponse asset = etf("SPY", "USD");
        buy(asset.getId(), today.minusDays(30), "10", "100", "0");
        price("SPY", "120", "USD");

        InvestmentDto.PositionResponse pos = service.getPortfolio(user, "EUR").getPositions().get(0);

        // 10 × 120 USD × 0,9 = 1080 EUR; costo 1000 USD × 0,9 = 900 EUR
        assertDecimal("1080", pos.getMarketValue());
        assertDecimal("900", pos.getCostBasis());
        assertDecimal("180", pos.getUnrealizedPl());
        assertDecimal("20", pos.getUnrealizedPlPercent());
        // Il prezzo resta nella valuta dell'asset
        assertDecimal("120", pos.getPrice());
        assertEquals("USD", pos.getPriceCurrency());
    }

    @Test
    void portfolio_missingExchangeRate_isIncompleteAndExcludedFromTotals() {
        InvestmentDto.AssetResponse ok = etf("VWCE.DE", "EUR");
        buy(ok.getId(), today.minusDays(30), "10", "100", "0");
        price("VWCE.DE", "110", "EUR");
        InvestmentDto.AssetResponse chf = etf("SMI", "CHF");
        buy(chf.getId(), today.minusDays(30), "1", "50", "0");
        price("SMI", "55", "CHF");

        InvestmentDto.PortfolioResponse portfolio = service.getPortfolio(user, "EUR");

        assertFalse(portfolio.isComplete());
        assertDecimal("1100", portfolio.getTotalValue());
        InvestmentDto.PositionResponse smi = portfolio.getPositions().stream()
                .filter(p -> "SMI".equals(p.getSymbol())).findFirst().orElseThrow();
        assertNull(smi.getMarketValue());
        assertNull(smi.getCostBasis());
    }

    @Test
    void portfolio_missingPrice_isIncompleteButPositionIsListed() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(30), "10", "100", "0");

        InvestmentDto.PortfolioResponse portfolio = service.getPortfolio(user, "EUR");

        assertFalse(portfolio.isComplete());
        assertEquals(1, portfolio.getPositions().size());
        assertNull(portfolio.getPositions().get(0).getPrice());
        assertNull(portfolio.getPositions().get(0).getMarketValue());
        assertDecimal("0", portfolio.getTotalValue());
    }

    @Test
    void portfolio_bondValuedAsPercentOfNominal() {
        InvestmentDto.AssetResponse btp = service.createAsset(user, InvestmentDto.AssetRequest.builder()
                .assetType(InvestmentAssetType.BOND).name("BTP 2029").currency("EUR").build());
        buy(btp.getId(), today.minusDays(30), "10000", "98.5", "0");
        // Il prezzo manuale arriva da MarketPriceService (mock): 101,35% del nominale
        when(marketPriceService.resolveAll(anyList())).thenAnswer(inv -> {
            Map<UUID, ResolvedPrice> result = new HashMap<>();
            for (InvestmentAsset a : inv.<List<InvestmentAsset>>getArgument(0)) {
                result.put(a.getId(), new ResolvedPrice(new BigDecimal("101.35"), "EUR", LocalDateTime.now(), PriceSource.MANUAL, false));
            }
            return result;
        });

        InvestmentDto.PositionResponse pos = service.getPortfolio(user, "EUR").getPositions().get(0);

        assertDecimal("10135", pos.getMarketValue());
        assertDecimal("9850", pos.getCostBasis());
        assertDecimal("285", pos.getUnrealizedPl());
        assertDecimal("98.5", pos.getAvgPrice());
    }

    @Test
    void portfolio_closedPositionKeepsRealizedPlAndIncome_andIsNotPriced() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(60), "10", "100", "0");
        sell(asset.getId(), today.minusDays(30), "10", "120");
        service.addOperation(user, asset.getId(), InvestmentDto.OperationRequest.builder()
                .type(InvestmentOperationType.DIVIDEND).operationDate(today.minusDays(45)).amount(new BigDecimal("7.5")).build());

        InvestmentDto.PortfolioResponse portfolio = service.getPortfolio(user, "EUR");

        // Nessun prezzo serve per una posizione chiusa: il portafoglio resta completo
        assertTrue(portfolio.isComplete());
        InvestmentDto.PositionResponse pos = portfolio.getPositions().get(0);
        assertDecimal("0", pos.getQuantity());
        assertDecimal("200", pos.getRealizedPl());
        assertDecimal("7.5", pos.getIncome());
        assertDecimal("200", portfolio.getRealizedPl());
        assertDecimal("7.5", portfolio.getIncome());
        assertDecimal("0", portfolio.getTotalValue());
    }

    @Test
    void portfolio_openPositionsListedBeforeClosedOnes() {
        InvestmentDto.AssetResponse closed = etf("AAA.DE", "EUR");
        buy(closed.getId(), today.minusDays(60), "1", "10", "0");
        sell(closed.getId(), today.minusDays(30), "1", "12");
        InvestmentDto.AssetResponse open = etf("ZZZ.DE", "EUR");
        buy(open.getId(), today.minusDays(60), "1", "10", "0");
        price("ZZZ.DE", "11", "EUR");

        List<InvestmentDto.PositionResponse> positions = service.getPortfolio(user, "EUR").getPositions();

        assertEquals("ZZZ.DE", positions.get(0).getSymbol());
        assertEquals("AAA.DE", positions.get(1).getSymbol());
    }

    @Test
    void portfolioCache_skipsIncompleteOrStaleResults() throws Exception {
        // L'espressione "unless" è SpEL: si valuta sulla annotation reale, come per il portafoglio crypto
        String unless = InvestmentPortfolioService.class
                .getMethod("getPortfolio", User.class, String.class)
                .getAnnotation(org.springframework.cache.annotation.Cacheable.class).unless();
        org.springframework.expression.ExpressionParser parser = new org.springframework.expression.spel.standard.SpelExpressionParser();

        InvestmentDto.PositionResponse fresh = InvestmentDto.PositionResponse.builder().stale(false).build();
        InvestmentDto.PositionResponse stale = InvestmentDto.PositionResponse.builder().stale(true).build();

        assertFalse(skipCache(parser, unless, true, List.of(fresh)), "completo e aggiornato: si cacha");
        assertFalse(skipCache(parser, unless, true, List.of()), "portafoglio vuoto: si cacha");
        assertTrue(skipCache(parser, unless, false, List.of(fresh)), "incompleto: non si cacha");
        assertTrue(skipCache(parser, unless, true, List.of(fresh, stale)), "prezzo non aggiornato: non si cacha");
    }

    private boolean skipCache(org.springframework.expression.ExpressionParser parser, String unless, boolean complete,
                              List<InvestmentDto.PositionResponse> positions) {
        var context = new org.springframework.expression.spel.support.StandardEvaluationContext();
        context.setVariable("result", InvestmentDto.PortfolioResponse.builder().complete(complete).positions(positions).build());
        return Boolean.TRUE.equals(parser.parseExpression(unless).getValue(context, Boolean.class));
    }

    @Test
    void portfolio_ofUserWithoutAssets_isEmptyAndComplete() {
        InvestmentDto.PortfolioResponse portfolio = service.getPortfolio(user, "EUR");

        assertTrue(portfolio.isComplete());
        assertTrue(portfolio.getPositions().isEmpty());
        assertDecimal("0", portfolio.getTotalValue());
        assertNull(portfolio.getUnrealizedPlPercent());
    }

    // ─── Performance e storico ──────────────────────────────────────────────────

    @Test
    void performance_periodWithFirstPurchase_startsFromZero() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(10), "10", "100", "0");
        service.addOperation(user, asset.getId(), InvestmentDto.OperationRequest.builder()
                .type(InvestmentOperationType.DIVIDEND).operationDate(today.minusDays(3)).amount(new BigDecimal("5")).build());
        price("VWCE.DE", "110", "EUR");

        InvestmentDto.PerformanceResponse perf = service.getPerformance(user, today.minusDays(30), today);

        assertDecimal("1000", perf.getInvested());
        assertDecimal("0", perf.getDivested());
        assertDecimal("5", perf.getIncome());
        assertDecimal("0", perf.getStartValue());
        assertDecimal("1100", perf.getEndValue());
        // (1100 - 0) - (1000 - 0) + 5
        assertDecimal("105", perf.getTotalGain());
    }

    @Test
    void performance_openingBalanceWithoutSnapshot_hasNoGain() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(100), "10", "100", "0");
        price("VWCE.DE", "110", "EUR");

        InvestmentDto.PerformanceResponse perf = service.getPerformance(user, today.minusDays(30), today);

        // Il valore a inizio periodo non è ricostruibile senza snapshot: meglio null che inventato
        assertNull(perf.getStartValue());
        assertNull(perf.getTotalGain());
        assertDecimal("0", perf.getInvested());
        assertDecimal("1100", perf.getEndValue());
    }

    @Test
    void performance_realizedPlOnlyCountsSalesInThePeriod() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(100), "10", "100", "0");
        sell(asset.getId(), today.minusDays(80), "2", "110");
        sell(asset.getId(), today.minusDays(10), "3", "120");
        price("VWCE.DE", "120", "EUR");

        InvestmentDto.PerformanceResponse perf = service.getPerformance(user, today.minusDays(30), today);

        assertDecimal("60", perf.getRealizedPl());
        assertDecimal("360", perf.getDivested());
        assertDecimal("0", perf.getInvested());
    }

    @Test
    void performance_endBeforeStart_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.getPerformance(user, today, today.minusDays(1)));
    }

    @Test
    void snapshot_isSavedAndAppearsInHistory() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(30), "10", "100", "0");
        price("VWCE.DE", "110", "EUR");

        service.takeSnapshot(user, today);
        service.takeSnapshot(user, today); // idempotente: stesso giorno = aggiornamento

        InvestmentDto.HistoryResponse history = service.getHistory(user, 12, null);
        assertEquals(1, history.getPoints().size());
        assertDecimal("1100", history.getPoints().get(0).getMarketValue());
        assertDecimal("1000", history.getPoints().get(0).getCostBasis());
    }

    @Test
    void snapshot_skippedWhenPortfolioIsIncomplete() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(30), "10", "100", "0");
        // nessun prezzo: uno snapshot parziale mostrerebbe un crollo fittizio

        service.takeSnapshot(user, today);

        assertTrue(service.getHistory(user, 12, null).getPoints().isEmpty());
    }

    @Test
    void snapshot_notWrittenForUserWithoutOpenPositionsAndNoHistory() {
        etf("VWCE.DE", "EUR");

        service.takeSnapshot(user, today);

        assertTrue(service.getHistory(user, 12, null).getPoints().isEmpty());
    }

    @Test
    void snapshot_afterFullLiquidationRecordsZero() {
        InvestmentDto.AssetResponse asset = etf("VWCE.DE", "EUR");
        buy(asset.getId(), today.minusDays(30), "10", "100", "0");
        price("VWCE.DE", "110", "EUR");
        service.takeSnapshot(user, today.minusDays(1));

        sell(asset.getId(), today, "10", "110");
        service.takeSnapshot(user, today);

        List<InvestmentDto.HistoryPoint> points = service.getHistory(user, 12, null).getPoints();
        assertEquals(2, points.size());
        assertDecimal("0", points.get(1).getMarketValue());
    }
}
