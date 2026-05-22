package it.iacovelli.nexabudgetbe.service.chat;

import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinanceToolRegistry {

    private final FinanceTools financeTools;

    public Tool buildFinanceTool() {
        return Tool.builder().functionDeclarations(
                FunctionDeclaration.builder()
                        .name("getAccountBalances")
                        .description("Restituisce la lista dei conti bancari dell'utente con saldo e valuta, più il saldo totale convertito nella valuta di default dell'utente.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("getRecentTransactions")
                        .description("Restituisce le ultime transazioni dell'utente. Limit massimo 50.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of("limit", Schema.builder().type("INTEGER")
                                        .description("Numero di transazioni da restituire (default 10, max 50)").build()))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getTransactionsInPeriod")
                        .description("Restituisce le transazioni dell'utente in un intervallo di date. Max 5 anni di range.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "startDate", Schema.builder().type("STRING").description("Data inizio in formato yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine in formato yyyy-MM-dd").build()))
                                .required(List.of("startDate", "endDate"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getPeriodTotals")
                        .description("Restituisce il totale entrate, uscite e saldo netto per un periodo specifico.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "startDate", Schema.builder().type("STRING").description("Data inizio in formato yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine in formato yyyy-MM-dd").build()))
                                .required(List.of("startDate", "endDate"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getActiveBudgets")
                        .description("Restituisce i budget attivi dell'utente con limite, importo speso e percentuale di utilizzo.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("getMonthlyTrend")
                        .description("Restituisce il trend mensile di entrate e uscite degli ultimi N mesi.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of("months", Schema.builder().type("INTEGER")
                                        .description("Numero di mesi da analizzare (default 6, max 24)").build()))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getCategoryBreakdown")
                        .description("Restituisce il breakdown delle spese per categoria in un intervallo di date.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "startDate", Schema.builder().type("STRING").description("Data inizio in formato yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine in formato yyyy-MM-dd").build()))
                                .required(List.of("startDate", "endDate"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getMonthlyProjection")
                        .description("Restituisce la proiezione di entrate e uscite per la fine del mese corrente basata sul ritmo attuale.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("getCryptoPortfolio")
                        .description("Restituisce il valore del portafoglio crypto dell'utente nella valuta di default.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("listCategories")
                        .description("Restituisce la lista delle categorie disponibili per l'utente (personali + predefinite).")
                        .build(),
                FunctionDeclaration.builder()
                        .name("searchTransactions")
                        .description("Ricerca avanzata delle transazioni con filtri opzionali: tipo (IN/OUT), nome categoria, intervallo di date, testo libero nella descrizione.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "type", Schema.builder().type("STRING").description("Tipo transazione: IN o OUT").build(),
                                        "categoryName", Schema.builder().type("STRING").description("Nome della categoria da filtrare").build(),
                                        "startDate", Schema.builder().type("STRING").description("Data inizio yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine yyyy-MM-dd").build(),
                                        "search", Schema.builder().type("STRING").description("Testo da cercare nella descrizione o nel nome del conto").build(),
                                        "limit", Schema.builder().type("INTEGER").description("Numero massimo di risultati (default 20, max 50)").build()))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getTransactionsByCategory")
                        .description("Restituisce tutte le transazioni di una specifica categoria dell'utente.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "categoryName", Schema.builder().type("STRING").description("Nome esatto della categoria").build()))
                                .required(List.of("categoryName"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getRemainingBudgets")
                        .description("Restituisce i budget attivi con il residuo rimanente (quanto è ancora disponibile) per categoria.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("getBudgetMonthlySummary")
                        .description("Restituisce il riepilogo mensile dei budget con limite, speso, residuo e percentuale di utilizzo per ciascuna categoria.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "date", Schema.builder().type("STRING").description("Data di riferimento yyyy-MM-dd (default: oggi)").build()))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getMonthComparison")
                        .description("Confronta entrate, uscite e netto di un mese specifico con il mese precedente.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "year", Schema.builder().type("INTEGER").description("Anno (es. 2026)").build(),
                                        "month", Schema.builder().type("INTEGER").description("Mese (1-12)").build()))
                                .required(List.of("year", "month"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getBalanceTrend")
                        .description("Restituisce l'andamento del saldo mese per mese in un intervallo di date, con saldo di apertura, netto mensile e saldo di chiusura.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "startDate", Schema.builder().type("STRING").description("Data inizio yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine yyyy-MM-dd").build()))
                                .required(List.of("startDate", "endDate"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getAccountsByType")
                        .description("Restituisce i conti bancari dell'utente filtrati per tipo (CHECKING, SAVINGS, CREDIT_CARD, INVESTMENT, CASH, OTHER).")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "type", Schema.builder().type("STRING").description("Tipo di conto: CHECKING, SAVINGS, CREDIT_CARD, INVESTMENT, CASH, OTHER").build()))
                                .required(List.of("type"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("convertCurrency")
                        .description("Converte un importo da una valuta a un'altra usando i tassi di cambio aggiornati.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "amount", Schema.builder().type("NUMBER").description("Importo da convertire").build(),
                                        "fromCurrency", Schema.builder().type("STRING").description("Valuta di origine (es. USD, EUR)").build(),
                                        "toCurrency", Schema.builder().type("STRING").description("Valuta di destinazione (es. EUR, USD)").build()))
                                .required(List.of("amount", "fromCurrency", "toCurrency"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getExchangeRate")
                        .description("Restituisce il tasso di cambio attuale tra due valute.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "fromCurrency", Schema.builder().type("STRING").description("Valuta di origine (es. USD, EUR)").build(),
                                        "toCurrency", Schema.builder().type("STRING").description("Valuta di destinazione (es. EUR, USD)").build()))
                                .required(List.of("fromCurrency", "toCurrency"))
                                .build())
                        .build()
        ).build();
    }

    public String dispatchTool(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "getAccountBalances" -> financeTools.getAccountBalances();
                case "getRecentTransactions" -> financeTools.getRecentTransactions(
                        args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : null);
                case "getTransactionsInPeriod" -> financeTools.getTransactionsInPeriod(
                        (String) args.get("startDate"), (String) args.get("endDate"));
                case "getPeriodTotals" -> financeTools.getPeriodTotals(
                        (String) args.get("startDate"), (String) args.get("endDate"));
                case "getActiveBudgets" -> financeTools.getActiveBudgets();
                case "getMonthlyTrend" -> financeTools.getMonthlyTrend(
                        args.containsKey("months") ? ((Number) args.get("months")).intValue() : null);
                case "getCategoryBreakdown" -> financeTools.getCategoryBreakdown(
                        (String) args.get("startDate"), (String) args.get("endDate"));
                case "getMonthlyProjection" -> financeTools.getMonthlyProjection();
                case "getCryptoPortfolio" -> financeTools.getCryptoPortfolio();
                case "listCategories" -> financeTools.listCategories();
                case "searchTransactions" -> financeTools.searchTransactions(
                        (String) args.get("type"),
                        (String) args.get("categoryName"),
                        (String) args.get("startDate"),
                        (String) args.get("endDate"),
                        (String) args.get("search"),
                        args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : null);
                case "getTransactionsByCategory" -> financeTools.getTransactionsByCategory(
                        (String) args.get("categoryName"));
                case "getRemainingBudgets" -> financeTools.getRemainingBudgets();
                case "getBudgetMonthlySummary" -> financeTools.getBudgetMonthlySummary(
                        (String) args.get("date"));
                case "getMonthComparison" -> financeTools.getMonthComparison(
                        ((Number) args.get("year")).intValue(),
                        ((Number) args.get("month")).intValue());
                case "getBalanceTrend" -> financeTools.getBalanceTrend(
                        (String) args.get("startDate"),
                        (String) args.get("endDate"));
                case "getAccountsByType" -> financeTools.getAccountsByType(
                        (String) args.get("type"));
                case "convertCurrency" -> financeTools.convertCurrency(
                        ((Number) args.get("amount")).doubleValue(),
                        (String) args.get("fromCurrency"),
                        (String) args.get("toCurrency"));
                case "getExchangeRate" -> financeTools.getExchangeRate(
                        (String) args.get("fromCurrency"),
                        (String) args.get("toCurrency"));
                default -> "Tool non trovato: " + name;
            };
        } catch (Exception e) {
            log.error("[FinanceToolRegistry] Errore esecuzione tool '{}': {}", name, e.getMessage());
            return "Errore nell'esecuzione del tool " + name + ": " + e.getMessage();
        }
    }
}
