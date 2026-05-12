package it.iacovelli.nexabudgetbe.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.Category;
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
}
