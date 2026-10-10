package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetDto;
import it.iacovelli.nexabudgetbe.dto.InvestmentDto;
import it.iacovelli.nexabudgetbe.dto.NetWorthDto;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.InvestmentAssetType;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiReportPdfServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private static final String MARKDOWN = """
            # Report finanziario

            ## 1. Riassunto Generale
            Nel periodo hai registrato **entrate per 16.800,00 €** e uscite per *12.430,50 €*, con un tasso di risparmio del 26% 🚀.
            Rispetto al mese precedente le uscite sono salite del 4% → attenzione alle spese variabili.
            Ad agosto il netto è stato di −118,40 €.

            ## 2. Analisi per Categoria
            | Categoria | Importo | Quota |
            |---|---:|---:|
            | Casa | 4.800,00 € | 38,6% |
            | **Spesa** | 2.140,30 € | 17,2% |
            | Trasporti | 1.020,00 € | 8,2% |

            ### Pattern e Anomalie
            - Abbonamenti ricorrenti: `Netflix`, Spotify e palestra (~65 €/mese)
            - Una spesa insolita di **890,00 €** da *Elettronica Shop* il 12/07
              - Nessun'altra spesa simile negli ultimi 6 mesi
            * Spesa alimentare stabile

            ---

            **Suggerimenti di Miglioramento**
            1. Imposta un budget per la categoria Ristoranti, oggi senza limite.
            2. Valuta di disdire gli abbonamenti poco usati.
            3. Sposta il 10% delle entrate su un conto risparmio a inizio mese.

            > Nota: i dati di settembre sono parziali.
            """;

    @Mock
    private ReportService reportService;

    @Mock
    private BudgetService budgetService;

    @Mock
    private InvestmentPortfolioService investmentPortfolioService;

    @Mock
    private NetWorthService netWorthService;

    @InjectMocks
    private AiReportPdfService service;

    private User user;

    @BeforeEach
    void setUp() {
        service.initFonts();
        user = new User();
        user.setUsername("mario.rossi");
        user.setDefaultCurrency("EUR");
    }

    @Test
    void buildReportPdf_withFullData_rendersAllSections() throws IOException {
        stubFullData();

        byte[] pdf = service.buildReportPdf(user, START, END, MARKDOWN);
        dumpIfRequested(pdf, "ai-report-full.pdf");

        String text = extractText(pdf);
        assertTrue(text.contains("Report finanziario"));
        assertTrue(text.contains("Analisi AI"));
        assertTrue(text.contains("Trend mensile"));
        assertTrue(text.contains("Budget del mese"));
        assertTrue(text.contains("Riassunto Generale"), "markdown headings must be rendered");
        assertTrue(text.contains("Suggerimenti di Miglioramento"), "bold-only lines must be rendered as headings");
        assertTrue(text.contains("16.800,00 €"), "Italian currency formatting expected");
        assertTrue(text.contains("-118,40 €"), "typographic minus must be rendered as a hyphen, not dropped");
        assertTrue(text.contains("Pagina 1 di"), "page footer expected");
        assertFalse(text.contains("**"), "markdown bold markers must be rendered, not printed");
        assertFalse(text.contains("|---"), "markdown table separator must not be printed");
    }

    @Test
    void buildReportPdf_withNoData_rendersPlaceholders() throws IOException {
        when(reportService.getMonthlyTrendByRange(any(), any(), any()))
                .thenReturn(ReportDto.MonthlyTrendResponse.builder().currency("EUR").items(List.of()).build());
        when(reportService.getCategoryBreakdown(any(), any(), any())).thenReturn(null);
        when(reportService.getMonthComparison(any(), anyInt(), anyInt())).thenReturn(null);
        when(reportService.getBalanceTrend(any(), any(), any())).thenReturn(null);
        when(reportService.getMonthlyProjection(any())).thenReturn(null);
        when(budgetService.getBudgetMonthlySummary(any(), any())).thenReturn(List.of());

        byte[] pdf = service.buildReportPdf(user, START, END, null);
        dumpIfRequested(pdf, "ai-report-empty.pdf");

        String text = extractText(pdf);
        assertTrue(text.contains("Nessun contenuto disponibile."));
        assertTrue(text.contains("Nessun budget attivo nel mese selezionato."));
    }

    @Test
    void buildReportPdf_withInvestments_rendersNetWorthPositionsAndPerformance() throws IOException {
        stubFullData();
        when(netWorthService.getNetWorth(any(), any())).thenReturn(NetWorthDto.NetWorthResponse.builder()
                .currency("EUR").total(new BigDecimal("30500.00"))
                .liquidity(new BigDecimal("12000.00")).crypto(new BigDecimal("3000.00")).investments(new BigDecimal("15500.00"))
                .liquidityPercent(new BigDecimal("39.3")).cryptoPercent(new BigDecimal("9.8")).investmentsPercent(new BigDecimal("50.8"))
                .complete(true).warnings(List.of()).build());
        when(investmentPortfolioService.getPortfolio(any(), any())).thenReturn(InvestmentDto.PortfolioResponse.builder()
                .currency("EUR").totalValue(new BigDecimal("15500.00")).complete(true)
                .positions(List.of(
                        InvestmentDto.PositionResponse.builder().name("Vanguard FTSE All-World").assetType(InvestmentAssetType.ETF)
                                .quantity(new BigDecimal("90")).marketValue(new BigDecimal("15500.00"))
                                .unrealizedPl(new BigDecimal("1500")).unrealizedPlPercent(new BigDecimal("10.7")).build(),
                        InvestmentDto.PositionResponse.builder().name("Vecchio ETF venduto").assetType(InvestmentAssetType.ETF)
                                .quantity(BigDecimal.ZERO).marketValue(BigDecimal.ZERO).build()))
                .build());
        when(investmentPortfolioService.getPerformance(any(), any(), any())).thenReturn(InvestmentDto.PerformanceResponse.builder()
                .currency("EUR").invested(new BigDecimal("2000.00")).divested(BigDecimal.ZERO)
                .realizedPl(BigDecimal.ZERO).income(new BigDecimal("45.00")).totalGain(new BigDecimal("612.50")).build());

        byte[] pdf = service.buildReportPdf(user, START, END, MARKDOWN);
        dumpIfRequested(pdf, "ai-report-investments.pdf");

        String text = extractText(pdf);
        assertTrue(text.contains("Patrimonio e investimenti"));
        assertTrue(text.contains("Patrimonio netto"));
        assertTrue(text.contains("30.500,00 €"));
        assertTrue(text.contains("Vanguard FTSE All-World"));
        assertFalse(text.contains("Vecchio ETF venduto"), "le posizioni chiuse non compaiono nella tabella");
        assertTrue(text.contains("Guadagno complessivo"));
        assertTrue(text.contains("+612,50 €"));
        assertFalse(text.contains("Patrimonio e investimenti non disponibili."));
    }

    @Test
    void buildReportPdf_incompleteNetWorth_isFlagged() throws IOException {
        stubFullData();
        when(netWorthService.getNetWorth(any(), any())).thenReturn(NetWorthDto.NetWorthResponse.builder()
                .currency("EUR").total(new BigDecimal("12000.00")).liquidity(new BigDecimal("12000.00"))
                .complete(false).warnings(List.of("Valore crypto non disponibile")).build());

        String text = extractText(service.buildReportPdf(user, START, END, MARKDOWN));

        assertTrue(text.contains("n/d"), "la voce non calcolabile è esplicita");
        assertTrue(text.contains("parziale"), "il totale parziale è dichiarato");
    }

    @Test
    void buildReportPdf_investmentServicesFail_stillProducesReport() throws IOException {
        stubFullData();
        when(netWorthService.getNetWorth(any(), any())).thenThrow(new IllegalStateException("provider giù"));

        String text = extractText(service.buildReportPdf(user, START, END, MARKDOWN));

        assertTrue(text.contains("Budget del mese"), "il resto del report non dipende dagli investimenti");
        assertTrue(text.contains("Patrimonio e investimenti non disponibili."));
    }

    @Test
    void buildFilename_usesPeriod() {
        assertEquals("report_finanziario_20260401_20260930.pdf", service.buildFilename(START, END));
    }

    private void stubFullData() {
        List<ReportDto.MonthlyTrendItem> trend = new ArrayList<>();
        List<ReportDto.BalanceTrendItem> balance = new ArrayList<>();
        double[] expenses = {1980.40, 2210.10, 1875.00, 2650.75, 1920.25, 1794.00};
        double running = 8200;
        for (int i = 0; i < 6; i++) {
            BigDecimal income = BigDecimal.valueOf(2800);
            BigDecimal expense = BigDecimal.valueOf(expenses[i]);
            trend.add(ReportDto.MonthlyTrendItem.builder()
                    .year(2026).month(4 + i).income(income).expense(expense).net(income.subtract(expense)).build());
            running += 2800 - expenses[i];
            balance.add(ReportDto.BalanceTrendItem.builder()
                    .year(2026).month(4 + i).monthlyNet(income.subtract(expense)).closingBalance(BigDecimal.valueOf(running)).build());
        }

        when(reportService.getMonthlyTrendByRange(any(), any(), any()))
                .thenReturn(ReportDto.MonthlyTrendResponse.builder().currency("EUR").items(trend).build());
        when(reportService.getCategoryBreakdown(any(), any(), any())).thenReturn(ReportDto.CategoryBreakdownResponse.builder()
                .currency("EUR")
                .totalExpense(BigDecimal.valueOf(9230.50))
                .totalIncome(BigDecimal.valueOf(16800))
                .categories(List.of(
                        category("Casa", 4800, 52.0, TransactionType.OUT),
                        category("Spesa alimentare", 2140.30, 23.2, TransactionType.OUT),
                        category("Trasporti", 1020, 11.1, TransactionType.OUT),
                        category("Ristoranti e bar", 890.20, 9.6, TransactionType.OUT),
                        category("Abbonamenti", 380, 4.1, TransactionType.OUT),
                        category("Stipendio", 16200, 96.4, TransactionType.IN),
                        category("Rimborsi", 600, 3.6, TransactionType.IN)))
                .build());
        when(reportService.getMonthComparison(any(), anyInt(), anyInt())).thenReturn(ReportDto.MonthComparisonResponse.builder()
                .currency("EUR")
                .previousMonth(ReportDto.MonthComparisonItem.builder().year(2026).month(8)
                        .income(BigDecimal.valueOf(2800)).expense(BigDecimal.valueOf(1920.25)).net(BigDecimal.valueOf(879.75)).build())
                .currentMonth(ReportDto.MonthComparisonItem.builder().year(2026).month(9)
                        .income(BigDecimal.valueOf(2800)).expense(BigDecimal.valueOf(1794)).net(BigDecimal.valueOf(1006)).build())
                .build());
        when(reportService.getBalanceTrend(any(), any(), any())).thenReturn(ReportDto.BalanceTrendResponse.builder()
                .currency("EUR").openingBalance(BigDecimal.valueOf(8200)).items(balance).build());
        when(reportService.getMonthlyProjection(any())).thenReturn(ReportDto.MonthlyProjection.builder()
                .year(2026).month(10).currency("EUR").daysElapsed(6).daysInMonth(31)
                .currentMonthIncome(BigDecimal.valueOf(2800)).projectedMonthlyIncome(BigDecimal.valueOf(2800))
                .currentMonthExpense(BigDecimal.valueOf(410.30)).projectedMonthlyExpense(BigDecimal.valueOf(2119.88))
                .build());
        when(budgetService.getBudgetMonthlySummary(any(), any())).thenReturn(List.of(
                budget("Spesa alimentare", 400, 312.40),
                budget("Ristoranti e bar", 150, 171.20),
                budget("Trasporti", 200, 96)));
    }

    private ReportDto.CategoryBreakdownItem category(String name, double net, double pct, TransactionType type) {
        return ReportDto.CategoryBreakdownItem.builder()
                .categoryId(UUID.randomUUID()).categoryName(name).net(BigDecimal.valueOf(net)).percentage(pct).inferredType(type).build();
    }

    private BudgetDto.MonthlySummaryResponse budget(String name, double limit, double spent) {
        return BudgetDto.MonthlySummaryResponse.builder()
                .budgetId(UUID.randomUUID()).categoryId(UUID.randomUUID()).categoryName(name)
                .limit(BigDecimal.valueOf(limit)).spent(BigDecimal.valueOf(spent)).remaining(BigDecimal.valueOf(limit - spent))
                .percentageUsed(spent / limit * 100)
                .periodStart(LocalDate.of(2026, 9, 1)).periodEnd(LocalDate.of(2026, 9, 30))
                .build();
    }

    private String extractText(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    /** Set -Dpdf.dump.dir=/some/dir to write the generated PDFs for visual inspection. */
    private void dumpIfRequested(byte[] pdf, String name) throws IOException {
        String dir = System.getProperty("pdf.dump.dir");
        if (dir != null) {
            Files.write(Path.of(dir, name), pdf);
        }
    }
}
