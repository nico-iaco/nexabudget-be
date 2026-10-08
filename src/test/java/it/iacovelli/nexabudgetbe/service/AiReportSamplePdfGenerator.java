package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetDto;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Generates the sample AI report PDF shipped in {@code docs/examples/} with fictional but self-consistent data
 * (aggregates, charts and AI text agree with each other). Skipped unless the output path is given:
 * <pre>./mvnw test -Dtest=AiReportSamplePdfGenerator -Dsample.pdf.out=docs/examples/report_finanziario_esempio.pdf</pre>
 */
@ExtendWith(MockitoExtension.class)
@EnabledIfSystemProperty(named = "sample.pdf.out", matches = ".+")
class AiReportSamplePdfGenerator {

    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    // {income, expense} per month, April → September.
    private static final double[][] MONTHS = {
            {2850.00, 2104.60}, {2850.00, 2287.35}, {3080.00, 2412.90},
            {4950.00, 3376.15}, {2850.00, 2968.40}, {2850.00, 2031.20}};
    private static final double OPENING_BALANCE = 12400.00;

    private static final String MARKDOWN = """
            ## 1. Riassunto Generale
            Tra il 1° aprile e il 30 settembre 2026 hai registrato **entrate per 19.430,00 €** e **uscite per 15.180,60 €**, \
            con un risparmio netto di **4.249,40 €**: un **tasso di risparmio del 21,9%**, in linea con l'obiettivo consigliato del 20%.

            Il saldo complessivo dei conti è passato da 12.400,00 € a **16.649,40 €** (+34,3%). Cinque mesi su sei si sono \
            chiusi in positivo; l'unica eccezione è **agosto (−118,40 €)**, dovuto alle spese per le vacanze. \
            Luglio è stato il mese migliore (+1.573,85 €) grazie alla quattordicesima di 2.100,00 €.

            Rispetto ad agosto, a settembre le uscite sono scese da 2.968,40 € a 2.031,20 € (**−31,6%**) e il netto mensile \
            è tornato positivo a **+818,80 €**, con un tasso di risparmio del 28,7%.

            ## 2. Analisi per Categoria
            Le prime tre categorie assorbono il **57,5%** della spesa del semestre:

            | Categoria | Importo | Quota | Media mensile |
            |---|---:|---:|---:|
            | Casa | 4.500,00 € | 29,6% | 750,00 € |
            | Spesa alimentare | 2.286,45 € | 15,1% | 381,08 € |
            | Viaggi e vacanze | 1.940,00 € | 12,8% | 323,33 € |
            | Shopping | 1.210,65 € | 8,0% | 201,78 € |
            | Ristoranti e bar | 1.118,70 € | 7,4% | 186,45 € |
            | Trasporti | 1.064,30 € | 7,0% | 177,38 € |

            **Confronto con i budget di settembre**
            - **Ristoranti e bar** è l'unica categoria fuori budget: 178,40 € spesi su 150,00 € (**118,9%**). \
            La media del semestre (186,45 €/mese) indica che il limite attuale non è realistico rispetto alle tue abitudini.
            - **Spesa alimentare** è al 90,7% (362,80 € su 400,00 €): sotto controllo, ma con poco margine.
            - Trasporti (82,3%), Shopping (64,2%) e Tempo libero (48,3%) sono ampiamente entro i limiti.
            - Viaggi e vacanze, Abbonamenti e Bollette non hanno un budget associato.

            ## 3. Pattern e Anomalie
            ### Spese ricorrenti
            - **Affitto**: 750,00 € addebitati puntualmente il 1° di ogni mese.
            - **Abbonamenti**: 82,96 €/mese in totale — Palestra FitActive 45,00 €, Netflix 13,99 €, Spotify 11,99 €, \
            Disney+ 8,99 € e iCloud+ 2,99 €. Su base annua valgono **995,52 €**.
            - **Ristoranti e consegne a domicilio**: 41 transazioni nel semestre (scontrino medio 27,29 €), \
            di cui 18 ordini Deliveroo e Glovo per 412,60 €, concentrati il venerdì e il sabato sera.

            ### Transazioni da verificare
            - **Possibile doppio addebito**: Enel Energia ha addebitato **87,40 €** sia il 03/06 sia il 04/06 \
            con la stessa causale. Le altre bollette del semestre sono mensili.
            - **Spesa insolita**: 649,00 € da *MediaWorld* il 14/07, da sola il 53,6% della categoria Shopping del semestre. \
            Probabilmente un acquisto una tantum (smartphone), ma vale la pena confermarlo.
            - **Picco estivo**: tra luglio e agosto si concentrano tutti i 1.940,00 € di Viaggi e vacanze \
            (Booking.com, Ryanair, autostrade), senza un accantonamento nei mesi precedenti.

            ## 4. Suggerimenti di Miglioramento
            1. **Verifica la bolletta di giugno**: contatta Enel Energia per il doppio addebito da 87,40 € e chiedi lo storno.
            2. **Rendi realistico il budget Ristoranti**: alzalo a 180,00 € oppure, se vuoi restare a 150,00 €, \
            dimezza gli ordini a domicilio — basterebbero 3 ordini in meno al mese per rientrare.
            3. **Razionalizza gli streaming**: Netflix e Disney+ insieme costano 22,98 €/mese (275,76 €/anno). \
            Alternarli invece di tenerli attivi entrambi vale circa 138,00 € di risparmio all'anno.
            4. **Crea un fondo vacanze**: accantonare circa 162,00 €/mese (1.940,00 € / 12) eviterebbe un mese \
            in negativo come agosto e distribuirebbe la spesa sull'anno.
            5. **Automatizza il risparmio**: il tuo risparmio medio è di 708,23 €/mese. Un bonifico automatico \
            di 600,00 € verso un conto deposito subito dopo lo stipendio ti mette al riparo dalle spese impulsive.

            > Nota: le transazioni del periodo sono state categorizzate al 97%; le poche rimaste senza categoria \
            sono incluse nei totali ma non nell'analisi per categoria.
            """;

    @Mock
    private ReportService reportService;

    @Mock
    private BudgetService budgetService;

    @InjectMocks
    private AiReportPdfService service;

    @Test
    void generateSamplePdf() throws IOException {
        service.initFonts();
        User user = new User();
        user.setUsername("utente.demo");
        user.setDefaultCurrency("EUR");
        stubData();

        Path out = Path.of(System.getProperty("sample.pdf.out"));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, service.buildReportPdf(user, START, END, MARKDOWN));
    }

    private void stubData() {
        List<ReportDto.MonthlyTrendItem> trend = new ArrayList<>();
        List<ReportDto.BalanceTrendItem> balance = new ArrayList<>();
        BigDecimal running = BigDecimal.valueOf(OPENING_BALANCE);
        for (int i = 0; i < MONTHS.length; i++) {
            BigDecimal income = BigDecimal.valueOf(MONTHS[i][0]);
            BigDecimal expense = BigDecimal.valueOf(MONTHS[i][1]);
            BigDecimal net = income.subtract(expense);
            running = running.add(net);
            trend.add(ReportDto.MonthlyTrendItem.builder()
                    .year(2026).month(4 + i).income(income).expense(expense).net(net).build());
            balance.add(ReportDto.BalanceTrendItem.builder()
                    .year(2026).month(4 + i).monthlyNet(net).closingBalance(running).build());
        }

        when(reportService.getMonthlyTrendByRange(any(), any(), any()))
                .thenReturn(ReportDto.MonthlyTrendResponse.builder().currency("EUR").items(trend).build());
        when(reportService.getCategoryBreakdown(any(), any(), any())).thenReturn(ReportDto.CategoryBreakdownResponse.builder()
                .currency("EUR")
                .totalExpense(BigDecimal.valueOf(15180.60))
                .totalIncome(BigDecimal.valueOf(19430.00))
                .categories(List.of(
                        category("Casa", 4500.00, 29.64, TransactionType.OUT),
                        category("Spesa alimentare", 2286.45, 15.06, TransactionType.OUT),
                        category("Viaggi e vacanze", 1940.00, 12.78, TransactionType.OUT),
                        category("Shopping", 1210.65, 7.97, TransactionType.OUT),
                        category("Ristoranti e bar", 1118.70, 7.37, TransactionType.OUT),
                        category("Trasporti", 1064.30, 7.01, TransactionType.OUT),
                        category("Bollette e utenze", 1042.80, 6.87, TransactionType.OUT),
                        category("Tempo libero", 698.30, 4.60, TransactionType.OUT),
                        category("Abbonamenti", 497.76, 3.28, TransactionType.OUT),
                        category("Salute", 412.40, 2.72, TransactionType.OUT),
                        category("Regali", 409.24, 2.70, TransactionType.OUT),
                        category("Stipendio", 19200.00, 98.82, TransactionType.IN),
                        category("Rimborsi", 230.00, 1.18, TransactionType.IN)))
                .build());
        when(reportService.getMonthComparison(any(), anyInt(), anyInt())).thenReturn(ReportDto.MonthComparisonResponse.builder()
                .currency("EUR")
                .previousMonth(ReportDto.MonthComparisonItem.builder().year(2026).month(8)
                        .income(BigDecimal.valueOf(2850.00)).expense(BigDecimal.valueOf(2968.40)).net(BigDecimal.valueOf(-118.40)).build())
                .currentMonth(ReportDto.MonthComparisonItem.builder().year(2026).month(9)
                        .income(BigDecimal.valueOf(2850.00)).expense(BigDecimal.valueOf(2031.20)).net(BigDecimal.valueOf(818.80)).build())
                .build());
        when(reportService.getBalanceTrend(any(), any(), any())).thenReturn(ReportDto.BalanceTrendResponse.builder()
                .currency("EUR").openingBalance(BigDecimal.valueOf(OPENING_BALANCE)).items(balance).build());
        // Projection is always on the current month: historic averages of the six months above.
        LocalDate today = LocalDate.now();
        when(reportService.getMonthlyProjection(any())).thenReturn(ReportDto.MonthlyProjection.builder()
                .year(today.getYear()).month(today.getMonthValue()).currency("EUR")
                .daysElapsed(today.getDayOfMonth()).daysInMonth(today.lengthOfMonth())
                .currentMonthIncome(BigDecimal.ZERO).projectedMonthlyIncome(BigDecimal.valueOf(3238.33))
                .currentMonthExpense(BigDecimal.valueOf(963.40)).projectedMonthlyExpense(BigDecimal.valueOf(2530.10))
                .build());
        when(budgetService.getBudgetMonthlySummary(any(), any())).thenReturn(List.of(
                budget("Spesa alimentare", 400, 362.80),
                budget("Ristoranti e bar", 150, 178.40),
                budget("Trasporti", 200, 164.50),
                budget("Shopping", 150, 96.30),
                budget("Tempo libero", 120, 58.00)));
    }

    private ReportDto.CategoryBreakdownItem category(String name, double net, double pct, TransactionType type) {
        return ReportDto.CategoryBreakdownItem.builder()
                .categoryId(UUID.randomUUID()).categoryName(name).net(BigDecimal.valueOf(net)).percentage(pct).inferredType(type).build();
    }

    private BudgetDto.MonthlySummaryResponse budget(String name, double limit, double spent) {
        return BudgetDto.MonthlySummaryResponse.builder()
                .budgetId(UUID.randomUUID()).categoryId(UUID.randomUUID()).categoryName(name)
                .limit(BigDecimal.valueOf(limit)).spent(BigDecimal.valueOf(spent))
                .remaining(BigDecimal.valueOf(limit).subtract(BigDecimal.valueOf(spent)))
                .percentageUsed(spent / limit * 100)
                .budgetStartDate(LocalDate.of(2026, 9, 1)).budgetEndDate(LocalDate.of(2026, 9, 30))
                .periodStart(LocalDate.of(2026, 9, 1)).periodEnd(LocalDate.of(2026, 9, 30))
                .build();
    }
}
