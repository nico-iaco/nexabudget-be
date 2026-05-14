package it.iacovelli.nexabudgetbe.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinanceTools {

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final BudgetService budgetService;
    private final CategoryService categoryService;
    private final ReportService reportService;
    private final CryptoPortfolioService cryptoPortfolioService;
    private final CurrencyConversionService currencyConversionService;
    private final ExchangeRateService exchangeRateService;
    private final ObjectMapper objectMapper;

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @Tool(name = "getAccountBalances", description = "Restituisce la lista dei conti bancari dell'utente con saldo e valuta, più il saldo totale convertito nella valuta di default dell'utente.")
    public String getAccountBalances() {
        User user = currentUser();
        var accounts = accountService.getAccountsByUser(user);
        BigDecimal total = accountService.getTotalConvertedBalance(user);
        StringBuilder sb = new StringBuilder();
        sb.append("Conti dell'utente:\n");
        for (var acc : accounts) {
            sb.append("- ").append(acc.getName())
              .append(" [").append(acc.getType()).append("]")
              .append(": ").append(acc.getActualBalance()).append(" ").append(acc.getCurrency()).append("\n");
        }
        sb.append("Totale convertito in ").append(user.getDefaultCurrency()).append(": ").append(total);
        return sb.toString();
    }

    @Tool(name = "getRecentTransactions", description = "Restituisce le ultime transazioni dell'utente. Limit massimo 50.")
    public String getRecentTransactions(
            @ToolParam(required = false, description = "Numero di transazioni da restituire (default 10, max 50)") Integer limit) {
        User user = currentUser();
        int effectiveLimit = limit == null ? 10 : Math.min(limit, 50);
        var page = transactionService.getTransactionsByUserPaged(user, PageRequest.of(0, effectiveLimit));
        StringBuilder sb = new StringBuilder();
        sb.append("Ultime ").append(effectiveLimit).append(" transazioni:\n");
        for (var tx : page.getContent()) {
            sb.append("- [").append(tx.getDate()).append("] ")
              .append(tx.getType()).append(" ")
              .append(tx.getAmount()).append(" | ")
              .append(tx.getDescription());
            if (tx.getCategoryName() != null) {
                sb.append(" | Categoria: ").append(tx.getCategoryName());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getTransactionsInPeriod", description = "Restituisce le transazioni dell'utente in un intervallo di date. Max 5 anni di range.")
    public String getTransactionsInPeriod(
            @ToolParam(required = true, description = "Data inizio in formato yyyy-MM-dd") String startDate,
            @ToolParam(required = true, description = "Data fine in formato yyyy-MM-dd") String endDate) {
        User user = currentUser();
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (ChronoUnit.YEARS.between(start, end) > 5) {
            return "Errore: il range non può superare 5 anni.";
        }
        var transactions = transactionService.getTransactionsByUserAndDateRange(user, start, end);
        if (transactions.isEmpty()) {
            return "Nessuna transazione trovata nel periodo " + start + " - " + end + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Transazioni dal ").append(start).append(" al ").append(end).append(" (").append(transactions.size()).append(" totali):\n");
        for (var tx : transactions) {
            sb.append("- [").append(tx.getDate()).append("] ")
              .append(tx.getType()).append(" ")
              .append(tx.getAmount()).append(" | ")
              .append(tx.getDescription());
            if (tx.getCategoryName() != null) {
                sb.append(" | ").append(tx.getCategoryName());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getPeriodTotals", description = "Restituisce il totale entrate, uscite e saldo netto per un periodo specifico.")
    public String getPeriodTotals(
            @ToolParam(required = true, description = "Data inizio in formato yyyy-MM-dd") String startDate,
            @ToolParam(required = true, description = "Data fine in formato yyyy-MM-dd") String endDate) {
        User user = currentUser();
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (ChronoUnit.YEARS.between(start, end) > 5) {
            return "Errore: il range non può superare 5 anni.";
        }
        var totals = transactionService.getTotalsForUserInPeriod(user, start, end);
        return "Periodo " + totals.getStartDate() + " - " + totals.getEndDate() + " (" + totals.getCurrency() + "):\n" +
               "Entrate: " + totals.getIncome() + "\n" +
               "Uscite: " + totals.getExpense() + "\n" +
               "Netto: " + totals.getNet();
    }

    @Tool(name = "getActiveBudgets", description = "Restituisce i budget attivi dell'utente con limite, importo speso e percentuale di utilizzo.")
    public String getActiveBudgets() {
        User user = currentUser();
        LocalDate today = LocalDate.now();
        Map<Budget, BigDecimal> usage = budgetService.getBudgetUsage(user, today);
        if (usage.isEmpty()) {
            return "Nessun budget attivo per oggi.";
        }
        StringBuilder sb = new StringBuilder("Budget attivi:\n");
        for (Map.Entry<Budget, BigDecimal> entry : usage.entrySet()) {
            Budget budget = entry.getKey();
            BigDecimal spent = entry.getValue();
            BigDecimal limit = budget.getBudgetLimit();
            BigDecimal pct = limit.compareTo(BigDecimal.ZERO) != 0
                    ? spent.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            sb.append("- ").append(budget.getCategory().getName())
              .append(": spesi ").append(spent)
              .append(" / limite ").append(limit)
              .append(" (").append(pct).append("%)")
              .append(" | periodo: ").append(budget.getStartDate()).append(" - ").append(budget.getEndDate())
              .append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getMonthlyTrend", description = "Restituisce il trend mensile di entrate e uscite degli ultimi N mesi.")
    public String getMonthlyTrend(
            @ToolParam(required = false, description = "Numero di mesi da analizzare (default 6, max 24)") Integer months) {
        User user = currentUser();
        int m = months == null ? 6 : Math.min(months, 24);
        var trend = reportService.getMonthlyTrend(user, m);
        StringBuilder sb = new StringBuilder("Trend mensile (" + trend.getCurrency() + ") ultimi " + m + " mesi:\n");
        for (var item : trend.getItems()) {
            sb.append(item.getYear()).append("/").append(String.format("%02d", item.getMonth()))
              .append(" → Entrate: ").append(item.getIncome())
              .append(", Uscite: ").append(item.getExpense())
              .append(", Netto: ").append(item.getNet()).append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getCategoryBreakdown", description = "Restituisce il breakdown delle spese per categoria in un intervallo di date.")
    public String getCategoryBreakdown(
            @ToolParam(required = true, description = "Data inizio in formato yyyy-MM-dd") String startDate,
            @ToolParam(required = true, description = "Data fine in formato yyyy-MM-dd") String endDate) {
        User user = currentUser();
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (ChronoUnit.YEARS.between(start, end) > 5) {
            return "Errore: il range non può superare 5 anni.";
        }
        var breakdown = reportService.getCategoryBreakdown(user, start, end);
        if (breakdown.getCategories().isEmpty()) {
            return "Nessuna transazione categorizzata nel periodo.";
        }
        StringBuilder sb = new StringBuilder("Breakdown categorie " + start + " - " + end + " (" + breakdown.getCurrency() + "):\n");
        for (var cat : breakdown.getCategories()) {
            sb.append("- ").append(cat.getCategoryName())
              .append(": ").append(cat.getNet())
              .append(" (").append(String.format("%.1f", cat.getPercentage())).append("%)")
              .append(" [").append(cat.getInferredType()).append("]\n");
        }
        return sb.toString();
    }

    @Tool(name = "getMonthlyProjection", description = "Restituisce la proiezione di entrate e uscite per la fine del mese corrente basata sul ritmo attuale.")
    public String getMonthlyProjection() {
        User user = currentUser();
        var projection = reportService.getMonthlyProjection(user);
        return "Proiezione mese corrente (" + projection.getCurrency() + ", " +
               projection.getDaysElapsed() + "/" + projection.getDaysInMonth() + " giorni trascorsi):\n" +
               "Entrate ad oggi: " + projection.getCurrentMonthIncome() + "\n" +
               "Uscite ad oggi: " + projection.getCurrentMonthExpense() + "\n" +
               "Entrate proiettate a fine mese: " + projection.getProjectedMonthlyIncome() + "\n" +
               "Uscite proiettate a fine mese: " + projection.getProjectedMonthlyExpense();
    }

    @Tool(name = "getCryptoPortfolio", description = "Restituisce il valore del portafoglio crypto dell'utente nella valuta di default.")
    public String getCryptoPortfolio() {
        User user = currentUser();
        try {
            var portfolio = cryptoPortfolioService.getPortfolioValue(user, user.getDefaultCurrency());
            StringBuilder sb = new StringBuilder("Portafoglio crypto (" + portfolio.getCurrency() + "):\n");
            sb.append("Valore totale: ").append(portfolio.getTotalValue()).append("\n");
            for (var asset : portfolio.getAssets()) {
                sb.append("- ").append(asset.getSymbol())
                  .append(": ").append(asset.getAmount()).append(" unità")
                  .append(" | valore: ").append(asset.getValue())
                  .append(" | prezzo: ").append(asset.getPrice()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[FinanceTools] Impossibile recuperare portfolio crypto: {}", e.getMessage());
            return "Portfolio crypto non disponibile o non configurato.";
        }
    }

    @Tool(name = "listCategories", description = "Restituisce la lista delle categorie disponibili per l'utente (personali + predefinite).")
    public String listCategories() {
        User user = currentUser();
        List<Category> categories = categoryService.getAllAvailableCategoriesForUser(user);
        String names = categories.stream().map(Category::getName).collect(Collectors.joining(", "));
        return "Categorie disponibili: " + names;
    }

    @Tool(name = "searchTransactions", description = "Ricerca avanzata delle transazioni con filtri opzionali: tipo (IN/OUT), nome categoria, intervallo di date, testo libero nella descrizione. Restituisce le transazioni che corrispondono ai criteri.")
    public String searchTransactions(
            @ToolParam(required = false, description = "Tipo di transazione: IN (entrate) o OUT (uscite)") String type,
            @ToolParam(required = false, description = "Nome della categoria da filtrare") String categoryName,
            @ToolParam(required = false, description = "Data inizio in formato yyyy-MM-dd") String startDate,
            @ToolParam(required = false, description = "Data fine in formato yyyy-MM-dd") String endDate,
            @ToolParam(required = false, description = "Testo da cercare nella descrizione o nel nome del conto") String search,
            @ToolParam(required = false, description = "Numero massimo di risultati (default 20, max 50)") Integer limit) {
        User user = currentUser();
        int effectiveLimit = limit == null ? 20 : Math.min(limit, 50);

        TransactionType txType = null;
        if (type != null && !type.isBlank()) {
            try {
                txType = TransactionType.valueOf(type.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return "Tipo non valido: '" + type + "'. Usa IN o OUT.";
            }
        }

        UUID categoryId = null;
        if (categoryName != null && !categoryName.isBlank()) {
            Optional<Category> cat = categoryService.getAllAvailableCategoriesForUser(user).stream()
                    .filter(c -> c.getName().equalsIgnoreCase(categoryName.trim()))
                    .findFirst();
            if (cat.isEmpty()) {
                return "Categoria non trovata: '" + categoryName + "'. Usa listCategories per vedere quelle disponibili.";
            }
            categoryId = cat.get().getId();
        }

        LocalDate start = startDate != null ? LocalDate.parse(startDate) : null;
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : null;

        var page = transactionService.getTransactionsFiltered(
                user.getId(), null, txType, categoryId, start, end,
                (search != null && !search.isBlank()) ? search.trim() : null,
                PageRequest.of(0, effectiveLimit));

        if (page.isEmpty()) {
            return "Nessuna transazione trovata con i filtri specificati.";
        }
        StringBuilder sb = new StringBuilder("Transazioni trovate (").append(page.getTotalElements()).append(" totali, mostrate ").append(page.getContent().size()).append("):\n");
        for (var tx : page.getContent()) {
            sb.append("- [").append(tx.getDate()).append("] ")
              .append(tx.getType()).append(" ").append(tx.getAmount())
              .append(" | ").append(tx.getDescription());
            if (tx.getCategoryName() != null) sb.append(" | ").append(tx.getCategoryName());
            sb.append(" | Conto: ").append(tx.getAccountName()).append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getTransactionsByCategory", description = "Restituisce tutte le transazioni di una specifica categoria dell'utente.")
    public String getTransactionsByCategory(
            @ToolParam(required = true, description = "Nome esatto della categoria (usa listCategories per vedere quelle disponibili)") String categoryName) {
        User user = currentUser();
        Optional<Category> cat = categoryService.getAllAvailableCategoriesForUser(user).stream()
                .filter(c -> c.getName().equalsIgnoreCase(categoryName.trim()))
                .findFirst();
        if (cat.isEmpty()) {
            return "Categoria non trovata: '" + categoryName + "'. Usa listCategories per vedere quelle disponibili.";
        }
        var transactions = transactionService.getTransactionsByCategoryAndUser(cat.get(), user);
        if (transactions.isEmpty()) {
            return "Nessuna transazione trovata per la categoria '" + cat.get().getName() + "'.";
        }
        StringBuilder sb = new StringBuilder("Transazioni per categoria '").append(cat.get().getName()).append("' (").append(transactions.size()).append(" totali):\n");
        for (var tx : transactions) {
            sb.append("- [").append(tx.getDate()).append("] ")
              .append(tx.getType()).append(" ").append(tx.getAmount())
              .append(" | ").append(tx.getDescription()).append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getRemainingBudgets", description = "Restituisce i budget attivi con il residuo rimanente (quanto è ancora disponibile) per categoria.")
    public String getRemainingBudgets() {
        User user = currentUser();
        Map<Budget, BigDecimal> remaining = budgetService.getRemainingBudgets(user, LocalDate.now());
        if (remaining.isEmpty()) {
            return "Nessun budget attivo per oggi.";
        }
        StringBuilder sb = new StringBuilder("Residuo budget attivi:\n");
        for (Map.Entry<Budget, BigDecimal> entry : remaining.entrySet()) {
            Budget budget = entry.getKey();
            BigDecimal rem = entry.getValue();
            BigDecimal limit = budget.getBudgetLimit();
            BigDecimal pct = limit.compareTo(BigDecimal.ZERO) != 0
                    ? rem.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            sb.append("- ").append(budget.getCategory().getName())
              .append(": residuo ").append(rem)
              .append(" / limite ").append(limit)
              .append(" (").append(pct).append("% rimasto)")
              .append(" | periodo: ").append(budget.getStartDate()).append(" - ").append(budget.getEndDate())
              .append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getBudgetMonthlySummary", description = "Restituisce il riepilogo mensile dei budget attivi con limite, speso, residuo e percentuale di utilizzo per ciascuna categoria.")
    public String getBudgetMonthlySummary(
            @ToolParam(required = false, description = "Data di riferimento in formato yyyy-MM-dd (default: oggi). Determina il mese da analizzare.") String date) {
        User user = currentUser();
        LocalDate refDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        var summaries = budgetService.getBudgetMonthlySummary(user, refDate);
        if (summaries.isEmpty()) {
            return "Nessun budget attivo per il mese di " + refDate.getYear() + "/" + String.format("%02d", refDate.getMonthValue()) + ".";
        }
        StringBuilder sb = new StringBuilder("Riepilogo budget ")
                .append(refDate.getYear()).append("/").append(String.format("%02d", refDate.getMonthValue())).append(":\n");
        for (var s : summaries) {
            sb.append("- ").append(s.getCategoryName())
              .append(": spesi ").append(s.getSpent())
              .append(" / limite ").append(s.getLimit())
              .append(" | residuo ").append(s.getRemaining())
              .append(" (").append(String.format("%.1f", s.getPercentageUsed())).append("% usato)\n");
        }
        return sb.toString();
    }

    @Tool(name = "getMonthComparison", description = "Confronta entrate, uscite e netto di un mese specifico con il mese precedente, mostrando la variazione.")
    public String getMonthComparison(
            @ToolParam(required = true, description = "Anno (es. 2026)") Integer year,
            @ToolParam(required = true, description = "Mese (1-12)") Integer month) {
        User user = currentUser();
        var cmp = reportService.getMonthComparison(user, year, month);
        var cur = cmp.getCurrentMonth();
        var prev = cmp.getPreviousMonth();
        return "Confronto mese " + cur.getYear() + "/" + String.format("%02d", cur.getMonth()) +
               " vs " + prev.getYear() + "/" + String.format("%02d", prev.getMonth()) +
               " (" + cmp.getCurrency() + "):\n" +
               "Mese corrente → Entrate: " + cur.getIncome() + ", Uscite: " + cur.getExpense() + ", Netto: " + cur.getNet() + "\n" +
               "Mese precedente → Entrate: " + prev.getIncome() + ", Uscite: " + prev.getExpense() + ", Netto: " + prev.getNet() + "\n" +
               "Variazione entrate: " + cmp.getIncomeChange() + "\n" +
               "Variazione uscite: " + cmp.getExpenseChange();
    }

    @Tool(name = "getBalanceTrend", description = "Restituisce l'andamento del saldo mese per mese in un intervallo di date, con saldo di apertura, netto mensile e saldo di chiusura per ogni mese.")
    public String getBalanceTrend(
            @ToolParam(required = true, description = "Data inizio in formato yyyy-MM-dd") String startDate,
            @ToolParam(required = true, description = "Data fine in formato yyyy-MM-dd") String endDate) {
        User user = currentUser();
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (ChronoUnit.YEARS.between(start, end) > 5) {
            return "Errore: il range non può superare 5 anni.";
        }
        var trend = reportService.getBalanceTrend(user, start, end);
        StringBuilder sb = new StringBuilder("Andamento saldo ")
                .append(trend.getStartDate()).append(" - ").append(trend.getEndDate())
                .append(" (").append(trend.getCurrency()).append("):\n")
                .append("Saldo iniziale: ").append(trend.getOpeningBalance()).append("\n");
        for (var item : trend.getItems()) {
            sb.append(item.getYear()).append("/").append(String.format("%02d", item.getMonth()))
              .append(" → Netto mensile: ").append(item.getMonthlyNet())
              .append(", Saldo finale: ").append(item.getClosingBalance()).append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "getAccountsByType", description = "Restituisce i conti bancari dell'utente filtrati per tipo (es. CHECKING, SAVINGS, CREDIT_CARD, INVESTMENT, CASH, OTHER).")
    public String getAccountsByType(
            @ToolParam(required = true, description = "Tipo di conto: CHECKING, SAVINGS, CREDIT_CARD, INVESTMENT, CASH, OTHER") String type) {
        User user = currentUser();
        AccountType accountType;
        try {
            accountType = AccountType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Tipo non valido: '" + type + "'. Valori ammessi: CHECKING, SAVINGS, CREDIT_CARD, INVESTMENT, CASH, OTHER.";
        }
        var accounts = accountService.getAccountsByUserAndType(user, accountType);
        if (accounts.isEmpty()) {
            return "Nessun conto di tipo " + accountType + ".";
        }
        StringBuilder sb = new StringBuilder("Conti di tipo ").append(accountType).append(":\n");
        for (var acc : accounts) {
            sb.append("- ").append(acc.getName())
              .append(": ").append(acc.getActualBalance()).append(" ").append(acc.getCurrency()).append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "convertCurrency", description = "Converte un importo da una valuta a un'altra usando i tassi di cambio aggiornati.")
    public String convertCurrency(
            @ToolParam(required = true, description = "Importo da convertire (numero)") Double amount,
            @ToolParam(required = true, description = "Valuta di origine (es. USD, EUR, GBP)") String fromCurrency,
            @ToolParam(required = true, description = "Valuta di destinazione (es. EUR, USD, GBP)") String toCurrency) {
        try {
            BigDecimal result = currencyConversionService.convert(
                    BigDecimal.valueOf(amount),
                    fromCurrency.trim().toUpperCase(),
                    toCurrency.trim().toUpperCase());
            return amount + " " + fromCurrency.toUpperCase() + " = " + result + " " + toCurrency.toUpperCase();
        } catch (Exception e) {
            log.warn("[FinanceTools] Errore conversione valuta: {}", e.getMessage());
            return "Impossibile convertire " + fromCurrency + " → " + toCurrency + ": " + e.getMessage();
        }
    }

    @Tool(name = "getExchangeRate", description = "Restituisce il tasso di cambio attuale tra due valute.")
    public String getExchangeRate(
            @ToolParam(required = true, description = "Valuta di origine (es. USD, EUR, GBP)") String fromCurrency,
            @ToolParam(required = true, description = "Valuta di destinazione (es. EUR, USD, GBP)") String toCurrency) {
        Optional<BigDecimal> rate = exchangeRateService.getRate(
                fromCurrency.trim().toUpperCase(),
                toCurrency.trim().toUpperCase());
        return rate.map(r -> "Tasso di cambio " + fromCurrency.toUpperCase() + " → " + toCurrency.toUpperCase() + ": " + r)
                   .orElse("Tasso di cambio non disponibile per " + fromCurrency + " → " + toCurrency + ".");
    }
}
