package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.dto.InvestmentDto;
import it.iacovelli.nexabudgetbe.dto.NetWorthDto;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Patrimonio netto = liquidità dei conti + crypto + investimenti. Una componente che non si riesce a calcolare
 * non viene nascosta: resta null, il totale la esclude e la risposta è marcata incompleta con il motivo.
 */
@Service
public class NetWorthService {

    private static final Logger logger = LoggerFactory.getLogger(NetWorthService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_HISTORY_MONTHS = 60;

    private final AccountService accountService;
    private final CryptoPortfolioService cryptoPortfolioService;
    private final InvestmentPortfolioService investmentPortfolioService;
    private final CryptoSnapshotService cryptoSnapshotService;
    private final CurrencyConversionService currencyConversionService;
    private final ReportService reportService;

    public NetWorthService(AccountService accountService, CryptoPortfolioService cryptoPortfolioService,
                           InvestmentPortfolioService investmentPortfolioService,
                           CryptoSnapshotService cryptoSnapshotService,
                           CurrencyConversionService currencyConversionService, ReportService reportService) {
        this.accountService = accountService;
        this.cryptoPortfolioService = cryptoPortfolioService;
        this.investmentPortfolioService = investmentPortfolioService;
        this.cryptoSnapshotService = cryptoSnapshotService;
        this.currencyConversionService = currencyConversionService;
        this.reportService = reportService;
    }

    public NetWorthDto.NetWorthResponse getNetWorth(User user, String currency) {
        String target = resolveCurrency(user, currency);
        String defaultCurrency = user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR";
        List<String> warnings = new ArrayList<>();

        BigDecimal liquidity = null;
        try {
            // Il saldo aggregato è nella valuta di default dell'utente: si riconverte solo se serve altro
            BigDecimal inDefault = accountService.getTotalConvertedBalance(user);
            liquidity = convert(inDefault, defaultCurrency, target);
            if (liquidity == null) {
                warnings.add("Tasso di cambio " + defaultCurrency + "→" + target + " non disponibile: liquidità esclusa");
            }
        } catch (Exception e) {
            logger.warn("Patrimonio netto: liquidità non calcolabile: {}", e.getMessage());
            warnings.add("Liquidità non disponibile");
        }

        BigDecimal crypto = null;
        try {
            CryptoDto.PortfolioValueResponse portfolio = cryptoPortfolioService.getPortfolioValue(user, target);
            // Con il cambio non disponibile il servizio risponde in USD: mai sommare USD come se fossero la valuta richiesta
            if (portfolio.getCurrency() != null && portfolio.getCurrency().equalsIgnoreCase(target)) {
                crypto = portfolio.getTotalValue();
                if (portfolio.getAssets() != null && portfolio.getAssets().stream().anyMatch(a -> a.getValue() == null)) {
                    warnings.add("Alcune crypto senza prezzo sono escluse dal valore");
                }
            } else {
                warnings.add("Valore crypto non convertibile in " + target + ": escluso");
            }
        } catch (Exception e) {
            logger.warn("Patrimonio netto: crypto non calcolabili: {}", e.getMessage());
            warnings.add("Valore crypto non disponibile");
        }

        BigDecimal investments = null;
        boolean investmentsComplete = true;
        try {
            InvestmentDto.PortfolioResponse portfolio = investmentPortfolioService.getPortfolio(user, target);
            investments = portfolio.getTotalValue();
            if (!portfolio.isComplete()) {
                investmentsComplete = false;
                warnings.add("Alcuni investimenti senza prezzo o tasso di cambio sono esclusi dal valore");
            }
        } catch (Exception e) {
            logger.warn("Patrimonio netto: investimenti non calcolabili: {}", e.getMessage());
            warnings.add("Valore investimenti non disponibile");
        }

        BigDecimal investmentAccounts = null;
        try {
            investmentAccounts = BigDecimal.ZERO;
            for (var account : accountService.getAccountsByUserAndType(user, AccountType.INVESTIMENTO)) {
                BigDecimal converted = convert(account.getActualBalance(), account.getCurrency(), target);
                if (converted != null) {
                    investmentAccounts = investmentAccounts.add(converted);
                }
            }
        } catch (Exception e) {
            logger.warn("Patrimonio netto: saldo conti investimento non calcolabile: {}", e.getMessage());
        }
        boolean doubleCounting = investmentAccounts != null && investmentAccounts.signum() != 0
                && investments != null && investments.signum() != 0;
        if (doubleCounting) {
            warnings.add("Hai conti di tipo INVESTIMENTO e asset di investimento: se rappresentano lo stesso broker "
                    + "il valore è contato due volte");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal part : new BigDecimal[]{liquidity, crypto, investments}) {
            if (part != null) {
                total = total.add(part);
            }
        }
        boolean complete = liquidity != null && crypto != null && investments != null && investmentsComplete;
        // Una liquidità negativa (scoperto) dà una quota negativa: le quote restano comunque sommabili a 100
        return NetWorthDto.NetWorthResponse.builder()
                .currency(target)
                .total(scale(total))
                .liquidity(scale(liquidity))
                .crypto(scale(crypto))
                .investments(scale(investments))
                .liquidityPercent(percent(liquidity, total))
                .cryptoPercent(percent(crypto, total))
                .investmentsPercent(percent(investments, total))
                .complete(complete)
                .warnings(warnings)
                .investmentAccountsBalance(scale(investmentAccounts))
                .possibleDoubleCounting(doubleCounting)
                .build();
    }

    /**
     * Serie mensile (fine mese) di liquidità + crypto + investimenti. Crypto e investimenti hanno storico solo
     * dal primo snapshot giornaliero: i mesi precedenti hanno quella voce null e valgono zero nel totale.
     */
    public NetWorthDto.NetWorthHistoryResponse getHistory(User user, int months, String currency) {
        int safeMonths = Math.max(1, Math.min(months, MAX_HISTORY_MONTHS));
        String target = resolveCurrency(user, currency);
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusMonths(safeMonths).withDayOfMonth(1);

        ReportDto.BalanceTrendResponse balance = reportService.getBalanceTrend(user, start, today);
        InvestmentDto.HistoryResponse investmentHistory = investmentPortfolioService.getHistory(user, safeMonths + 1, target);
        List<CryptoSnapshotService.Point> cryptoHistory = cryptoSnapshotService.getHistory(user, safeMonths + 1, target);

        List<NetWorthDto.NetWorthPoint> points = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(start); !ym.isAfter(YearMonth.from(today)); ym = ym.plusMonths(1)) {
            LocalDate monthEnd = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
            // Il trend dei saldi è nella valuta di default dell'utente: si converte se si è chiesta un'altra valuta
            BigDecimal liquidity = convert(balance.getItems().stream()
                    .filter(i -> i.getYear() == monthEnd.getYear() && i.getMonth() == monthEnd.getMonthValue())
                    .map(ReportDto.BalanceTrendItem::getClosingBalance)
                    .findFirst().orElse(null), balance.getCurrency(), target);
            BigDecimal investments = investmentHistory.getPoints().stream()
                    .filter(p -> !p.getDate().isAfter(monthEnd) && !p.getDate().isBefore(monthEnd.minusDays(7)))
                    .reduce((a, b) -> b) // ultimo snapshot del mese (serie ordinata per data)
                    .map(InvestmentDto.HistoryPoint::getMarketValue)
                    .orElse(null);
            BigDecimal crypto = cryptoHistory.stream()
                    .filter(p -> !p.date().isAfter(monthEnd) && !p.date().isBefore(monthEnd.minusDays(7)))
                    .reduce((a, b) -> b) // ultimo snapshot del mese (serie ordinata per data)
                    .map(CryptoSnapshotService.Point::value)
                    .orElse(null);
            BigDecimal total = (liquidity != null ? liquidity : BigDecimal.ZERO)
                    .add(crypto != null ? crypto : BigDecimal.ZERO)
                    .add(investments != null ? investments : BigDecimal.ZERO);
            points.add(NetWorthDto.NetWorthPoint.builder()
                    .date(monthEnd)
                    .liquidity(liquidity)
                    .crypto(crypto)
                    .investments(investments)
                    .total(scale(total))
                    .build());
        }
        return NetWorthDto.NetWorthHistoryResponse.builder()
                .currency(target)
                .points(points)
                .cryptoIncludedInHistory(true)
                .build();
    }

    private BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) {
            return null;
        }
        Optional<BigDecimal> rate = currencyConversionService.getRate(from, to);
        return rate.map(r -> amount.multiply(r).setScale(2, RoundingMode.HALF_UP)).orElse(null);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.signum() <= 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private String resolveCurrency(User user, String currency) {
        if (currency != null && !currency.isBlank()) {
            return currency.trim().toUpperCase();
        }
        return user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR";
    }
}
