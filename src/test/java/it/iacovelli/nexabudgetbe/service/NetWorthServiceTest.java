package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.dto.InvestmentDto;
import it.iacovelli.nexabudgetbe.dto.NetWorthDto;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetWorthServiceTest {

    private final AccountService accountService = mock(AccountService.class);
    private final CryptoPortfolioService cryptoService = mock(CryptoPortfolioService.class);
    private final InvestmentPortfolioService investmentService = mock(InvestmentPortfolioService.class);
    private final CurrencyConversionService conversion = mock(CurrencyConversionService.class);
    private final ReportService reportService = mock(ReportService.class);
    private final CryptoSnapshotService cryptoSnapshotService = mock(CryptoSnapshotService.class);
    private NetWorthService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new NetWorthService(accountService, cryptoService, investmentService, cryptoSnapshotService, conversion, reportService);
        user = User.builder().username("u").email("u@test.com").passwordHash("x").defaultCurrency("EUR").build();
        user.setId(java.util.UUID.randomUUID());
        when(conversion.getRate(anyString(), anyString())).thenAnswer(inv ->
                inv.getArgument(0).equals(inv.getArgument(1)) ? Optional.of(BigDecimal.ONE) : Optional.empty());
        when(accountService.getTotalConvertedBalance(user)).thenReturn(new BigDecimal("10000"));
        when(accountService.getAccountsByUserAndType(user, AccountType.INVESTIMENTO)).thenReturn(List.of());
        when(cryptoService.getPortfolioValue(user, "EUR")).thenReturn(crypto("EUR", "2000"));
        when(investmentService.getPortfolio(user, "EUR")).thenReturn(investments("8000", true));
    }

    private static CryptoDto.PortfolioValueResponse crypto(String currency, String total) {
        return CryptoDto.PortfolioValueResponse.builder().currency(currency).totalValue(new BigDecimal(total)).assets(List.of()).build();
    }

    private static InvestmentDto.PortfolioResponse investments(String total, boolean complete) {
        return InvestmentDto.PortfolioResponse.builder().currency("EUR").totalValue(new BigDecimal(total))
                .complete(complete).positions(List.of()).build();
    }

    @Test
    void sumsAllThreeComponents() {
        NetWorthDto.NetWorthResponse nw = service.getNetWorth(user, null);

        assertEquals("EUR", nw.getCurrency());
        assertEquals(0, new BigDecimal("20000").compareTo(nw.getTotal()));
        assertEquals(0, new BigDecimal("50").compareTo(nw.getLiquidityPercent()));
        assertEquals(0, new BigDecimal("10").compareTo(nw.getCryptoPercent()));
        assertEquals(0, new BigDecimal("40").compareTo(nw.getInvestmentsPercent()));
        assertTrue(nw.isComplete());
        assertTrue(nw.getWarnings().isEmpty());
        assertFalse(nw.isPossibleDoubleCounting());
    }

    @Test
    void cryptoReturnedInUsd_isExcludedNotSummedAsEuro() {
        // Il servizio crypto degrada a USD se manca il cambio: sommarlo falserebbe il totale
        when(cryptoService.getPortfolioValue(user, "EUR")).thenReturn(crypto("USD", "2000"));

        NetWorthDto.NetWorthResponse nw = service.getNetWorth(user, "EUR");

        assertNull(nw.getCrypto());
        assertEquals(0, new BigDecimal("18000").compareTo(nw.getTotal()));
        assertFalse(nw.isComplete());
        assertEquals(1, nw.getWarnings().size());
    }

    @Test
    void failingComponent_isNullAndFlagged_othersStillCounted() {
        when(investmentService.getPortfolio(user, "EUR")).thenThrow(new IllegalStateException("boom"));

        NetWorthDto.NetWorthResponse nw = service.getNetWorth(user, "EUR");

        assertNull(nw.getInvestments());
        assertEquals(0, new BigDecimal("12000").compareTo(nw.getTotal()));
        assertFalse(nw.isComplete());
    }

    @Test
    void incompleteInvestments_arePartialAndWarned() {
        when(investmentService.getPortfolio(user, "EUR")).thenReturn(investments("8000", false));

        NetWorthDto.NetWorthResponse nw = service.getNetWorth(user, "EUR");

        assertEquals(0, new BigDecimal("8000").compareTo(nw.getInvestments()));
        assertFalse(nw.isComplete());
        assertFalse(nw.getWarnings().isEmpty());
    }

    @Test
    void investmentAccountsAndAssets_flagPossibleDoubleCounting() {
        when(accountService.getAccountsByUserAndType(user, AccountType.INVESTIMENTO)).thenReturn(List.of(
                AccountDto.AccountResponse.builder().name("Broker").type(AccountType.INVESTIMENTO)
                        .actualBalance(new BigDecimal("5000")).currency("EUR").build()));

        NetWorthDto.NetWorthResponse nw = service.getNetWorth(user, "EUR");

        assertTrue(nw.isPossibleDoubleCounting());
        assertEquals(0, new BigDecimal("5000").compareTo(nw.getInvestmentAccountsBalance()));
    }

    @Test
    void investmentAccountWithoutAssets_isNotDoubleCounting() {
        when(investmentService.getPortfolio(user, "EUR")).thenReturn(investments("0", true));
        when(accountService.getAccountsByUserAndType(user, AccountType.INVESTIMENTO)).thenReturn(List.of(
                AccountDto.AccountResponse.builder().name("Broker").actualBalance(new BigDecimal("5000")).currency("EUR").build()));

        assertFalse(service.getNetWorth(user, "EUR").isPossibleDoubleCounting());
    }

    @Test
    void otherCurrency_withoutRate_excludesLiquidity() {
        when(cryptoService.getPortfolioValue(user, "USD")).thenReturn(crypto("USD", "100"));
        when(investmentService.getPortfolio(user, "USD")).thenReturn(investments("200", true));

        NetWorthDto.NetWorthResponse nw = service.getNetWorth(user, "USD");

        // EUR→USD non disponibile: la liquidità non si può esprimere in USD e non viene sommata come se lo fosse
        assertNull(nw.getLiquidity());
        assertEquals(0, new BigDecimal("300").compareTo(nw.getTotal()));
        assertFalse(nw.isComplete());
    }

    @Test
    void negativeLiquidity_givesNegativeShare_andSharesStillSumTo100() {
        when(accountService.getTotalConvertedBalance(user)).thenReturn(new BigDecimal("-500"));

        NetWorthDto.NetWorthResponse nw = service.getNetWorth(user, "EUR");

        assertEquals(0, new BigDecimal("9500").compareTo(nw.getTotal()));
        assertEquals(0, new BigDecimal("-5.26").compareTo(nw.getLiquidityPercent()));
        BigDecimal sum = nw.getLiquidityPercent().add(nw.getCryptoPercent()).add(nw.getInvestmentsPercent());
        assertTrue(sum.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.02")) <= 0);
    }

    @Test
    void history_combinesMonthlyBalanceAndLastSnapshotOfTheMonth() {
        LocalDate today = LocalDate.now();
        LocalDate prevMonthEnd = today.minusMonths(1).withDayOfMonth(today.minusMonths(1).lengthOfMonth());
        when(reportService.getBalanceTrend(eq(user), any(), any())).thenReturn(ReportDto.BalanceTrendResponse.builder()
                .currency("EUR").items(List.of(
                        ReportDto.BalanceTrendItem.builder().year(prevMonthEnd.getYear()).month(prevMonthEnd.getMonthValue())
                                .closingBalance(new BigDecimal("9000")).build(),
                        ReportDto.BalanceTrendItem.builder().year(today.getYear()).month(today.getMonthValue())
                                .closingBalance(new BigDecimal("9500")).build())).build());
        when(investmentService.getHistory(eq(user), org.mockito.ArgumentMatchers.anyInt(), eq("EUR")))
                .thenReturn(InvestmentDto.HistoryResponse.builder().currency("EUR").points(List.of(
                        InvestmentDto.HistoryPoint.builder().date(prevMonthEnd.minusDays(2)).marketValue(new BigDecimal("4000")).build(),
                        InvestmentDto.HistoryPoint.builder().date(prevMonthEnd).marketValue(new BigDecimal("4100")).build()))
                        .build());

        when(cryptoSnapshotService.getHistory(eq(user), org.mockito.ArgumentMatchers.anyInt(), eq("EUR")))
                .thenReturn(List.of(
                        new CryptoSnapshotService.Point(prevMonthEnd.minusDays(1), new BigDecimal("1900")),
                        new CryptoSnapshotService.Point(prevMonthEnd, new BigDecimal("2000"))));

        NetWorthDto.NetWorthHistoryResponse history = service.getHistory(user, 1, "EUR");

        assertTrue(history.isCryptoIncludedInHistory());
        NetWorthDto.NetWorthPoint prev = history.getPoints().stream()
                .filter(p -> p.getDate().equals(prevMonthEnd)).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("9000").compareTo(prev.getLiquidity()));
        assertEquals(0, new BigDecimal("4100").compareTo(prev.getInvestments()), "ultimo snapshot del mese");
        assertEquals(0, new BigDecimal("2000").compareTo(prev.getCrypto()), "ultimo snapshot crypto del mese");
        assertEquals(0, new BigDecimal("15100").compareTo(prev.getTotal()));
        NetWorthDto.NetWorthPoint current = history.getPoints().get(history.getPoints().size() - 1);
        assertNull(current.getInvestments());
        assertNull(current.getCrypto(), "nessuno snapshot crypto nel mese: null, zero nel totale");
        assertEquals(0, new BigDecimal("9500").compareTo(current.getTotal()));
    }
}
