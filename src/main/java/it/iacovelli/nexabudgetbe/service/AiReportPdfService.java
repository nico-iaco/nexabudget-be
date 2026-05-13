package it.iacovelli.nexabudgetbe.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import it.iacovelli.nexabudgetbe.dto.BudgetDto;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.CategorySeries;
import org.knowm.xchart.style.CategoryStyler;
import org.knowm.xchart.style.Styler.LegendLayout;
import org.knowm.xchart.style.Styler.LegendPosition;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportPdfService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReportService reportService;
    private final BudgetService budgetService;

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().escapeHtml(true).build();

    public byte[] buildReportPdf(User user, LocalDate startDate, LocalDate endDate, String reportMarkdown) {
        ReportDto.MonthlyTrendResponse monthlyTrend = reportService.getMonthlyTrendByRange(user, startDate, endDate);
        ReportDto.CategoryBreakdownResponse categoryBreakdown = reportService.getCategoryBreakdown(user, startDate, endDate);
        ReportDto.MonthComparisonResponse monthComparison = reportService.getMonthComparison(user, endDate.getYear(), endDate.getMonthValue());
        ReportDto.BalanceTrendResponse balanceTrend = reportService.getBalanceTrend(user, startDate, endDate);
        ReportDto.MonthlyProjection projection = reportService.getMonthlyProjection(user);
        List<BudgetDto.MonthlySummaryResponse> budgetSummary = budgetService.getBudgetMonthlySummary(user, endDate);

        String aiHtml = renderMarkdown(reportMarkdown);
        String monthlyTrendChart = renderMonthlyTrendChart(monthlyTrend);
        String categoryBreakdownChart = renderCategoryBreakdownChart(categoryBreakdown);
        String monthComparisonChart = renderMonthComparisonChart(monthComparison);
        String balanceTrendChart = renderBalanceTrendChart(balanceTrend);
        String projectionChart = renderProjectionChart(projection);

        String html = buildHtml(user, startDate, endDate, monthlyTrend, aiHtml,
                monthlyTrendChart, categoryBreakdownChart, monthComparisonChart,
                balanceTrendChart, projectionChart, budgetSummary);

        return renderPdf(html);
    }

    public String buildFilename(LocalDate startDate, LocalDate endDate) {
        return "report_finanziario_" + startDate.format(FILE_FORMAT) + "_" + endDate.format(FILE_FORMAT) + ".pdf";
    }

    private String buildHtml(User user,
                             LocalDate startDate,
                             LocalDate endDate,
                             ReportDto.MonthlyTrendResponse monthlyTrend,
                             String aiHtml,
                             String monthlyTrendChart,
                             String categoryBreakdownChart,
                             String monthComparisonChart,
                             String balanceTrendChart,
                             String projectionChart,
                             List<BudgetDto.MonthlySummaryResponse> budgetSummary) {

        String username = user != null && user.getUsername() != null ? user.getUsername() : "";
        String currency = monthlyTrend.getCurrency() != null ? monthlyTrend.getCurrency()
                : (user != null && user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR");

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>")
          .append("<html><head><meta charset=\"UTF-8\">")
          .append("<style>")
          .append("@page { size: A4; margin: 22mm 18mm; }")
          .append("body { font-family: Helvetica, Arial, sans-serif; color: #222; font-size: 12px; }")
          .append("h1 { font-size: 20px; margin: 0 0 4px 0; }")
          .append("h2 { font-size: 15px; margin: 18px 0 8px 0; border-bottom: 1px solid #e0e0e0; padding-bottom: 4px; }")
          .append("h3 { font-size: 13px; margin: 12px 0 6px 0; }")
          .append("p { margin: 6px 0; }")
          .append(".meta { font-size: 12px; color: #555; }")
          .append(".section { margin-top: 14px; }")
          .append(".ai-report { background: #f8f8f8; padding: 12px; border-radius: 6px; }")
          .append(".ai-report pre { white-space: pre-wrap; }")
          .append(".chart { width: 100%; height: auto; margin-top: 6px; }")
          .append(".chart-placeholder { color: #777; font-style: italic; }")
          .append("table { width: 100%; border-collapse: collapse; margin-top: 8px; }")
          .append("th, td { border: 1px solid #ddd; padding: 6px; text-align: left; font-size: 11px; }")
          .append("th { background: #f2f2f2; }")
          .append(".footer { margin-top: 18px; font-size: 10px; color: #777; }")
          .append("</style></head><body>");

        sb.append("<h1>Report finanziario nexaBudget</h1>")
          .append("<div class=\"meta\">Utente: <strong>")
          .append(HtmlUtils.htmlEscape(username))
          .append("</strong></div>")
          .append("<div class=\"meta\">Periodo: ")
          .append(startDate.format(DATE_FORMAT))
          .append(" - ")
          .append(endDate.format(DATE_FORMAT))
          .append("</div>");

        sb.append("<div class=\"section\"><h2>Report AI</h2>")
          .append("<div class=\"ai-report\">")
          .append(aiHtml)
          .append("</div></div>");

        appendChartSection(sb, "Trend mensile", "Entrate, uscite e saldo (" + currency + ")", monthlyTrendChart);
        appendChartSection(sb, "Breakdown per categoria", "Distribuzione per categoria (" + currency + ")", categoryBreakdownChart);
        appendChartSection(sb, "Confronto mese", "Mese corrente vs mese precedente (" + currency + ")", monthComparisonChart);
        appendChartSection(sb, "Andamento saldo", "Saldo cumulato di chiusura (" + currency + ")", balanceTrendChart);
        appendChartSection(sb, "Proiezione mensile", "Attuale vs proiezione fine mese (" + currency + ")", projectionChart);

        sb.append("<div class=\"section\"><h2>Budget mensili").append(" (" + currency + ")</h2>");
        if (budgetSummary == null || budgetSummary.isEmpty()) {
            sb.append("<p class=\"chart-placeholder\">Nessun budget attivo nel mese selezionato.</p>");
        } else {
            sb.append("<table><thead><tr>")
              .append("<th>Categoria</th>")
              .append("<th>Limite</th>")
              .append("<th>Speso</th>")
              .append("<th>Residuo</th>")
              .append("<th>% Utilizzo</th>")
              .append("<th>Periodo</th>")
              .append("</tr></thead><tbody>");

            for (BudgetDto.MonthlySummaryResponse item : budgetSummary) {
                sb.append("<tr>")
                  .append("<td>").append(HtmlUtils.htmlEscape(item.getCategoryName())).append("</td>")
                  .append("<td>").append(formatAmount(item.getLimit())).append("</td>")
                  .append("<td>").append(formatAmount(item.getSpent())).append("</td>")
                  .append("<td>").append(formatAmount(item.getRemaining())).append("</td>")
                  .append("<td>").append(formatPercentage(item.getPercentageUsed())).append("</td>")
                  .append("<td>")
                  .append(item.getPeriodStart().format(MONTH_FORMAT))
                  .append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div>");

        sb.append("<div class=\"footer\">Generato il ")
          .append(LocalDate.now().format(DATE_FORMAT))
          .append("</div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private void appendChartSection(StringBuilder sb, String title, String subtitle, String dataUri) {
        sb.append("<div class=\"section\"><h2>")
          .append(HtmlUtils.htmlEscape(title))
          .append("</h2>")
          .append("<div class=\"meta\">")
          .append(HtmlUtils.htmlEscape(subtitle))
          .append("</div>");

        if (dataUri == null) {
            sb.append("<p class=\"chart-placeholder\">Nessun dato disponibile per questo grafico.</p>");
        } else {
            sb.append("<img class=\"chart\" src=\"")
              .append(dataUri)
              .append("\" alt=\"")
              .append(HtmlUtils.htmlEscape(title))
              .append("\">");
        }
        sb.append("</div>");
    }

    private String renderMonthlyTrendChart(ReportDto.MonthlyTrendResponse monthlyTrend) {
        if (monthlyTrend == null || monthlyTrend.getItems() == null || monthlyTrend.getItems().isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        List<Double> income = new ArrayList<>();
        List<Double> expense = new ArrayList<>();
        List<Double> net = new ArrayList<>();

        for (ReportDto.MonthlyTrendItem item : monthlyTrend.getItems()) {
            labels.add(String.format("%02d/%d", item.getMonth(), item.getYear()));
            income.add(toDouble(item.getIncome()));
            expense.add(toDouble(item.getExpense()));
            net.add(toDouble(item.getNet()));
        }

        CategoryChart chart = new CategoryChartBuilder()
                .width(900).height(420)
                .title("Trend mensile")
                .xAxisTitle("Mese")
                .yAxisTitle("Importo")
                .build();
        styleLineChart(chart);
        chart.addSeries("Entrate", labels, income);
        chart.addSeries("Uscite", labels, expense);
        chart.addSeries("Netto", labels, net);
        applyLineMarkers(chart);
        return toDataUri(chart);
    }

    private String renderCategoryBreakdownChart(ReportDto.CategoryBreakdownResponse breakdown) {
        if (breakdown == null || breakdown.getCategories() == null || breakdown.getCategories().isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        List<Double> outValues = new ArrayList<>();
        List<Double> inValues = new ArrayList<>();

        for (ReportDto.CategoryBreakdownItem item : breakdown.getCategories()) {
            labels.add(shortLabel(item.getCategoryName(), 24));
            if (item.getInferredType() == TransactionType.OUT) {
                outValues.add(toDouble(item.getNet()));
                inValues.add(0.0);
            } else {
                outValues.add(0.0);
                inValues.add(toDouble(item.getNet()));
            }
        }

        CategoryChart chart = new CategoryChartBuilder()
                .width(900).height(420)
                .title("Breakdown per categoria")
                .xAxisTitle("Categoria")
                .yAxisTitle("Importo")
                .build();
        styleBarChart(chart, true);
        chart.addSeries("Uscite", labels, outValues);
        chart.addSeries("Entrate", labels, inValues);
        return toDataUri(chart);
    }

    private String renderMonthComparisonChart(ReportDto.MonthComparisonResponse comparison) {
        if (comparison == null || comparison.getCurrentMonth() == null || comparison.getPreviousMonth() == null) {
            return null;
        }
        List<String> labels = List.of("Mese corrente", "Mese precedente");
        List<Double> incomes = List.of(toDouble(comparison.getCurrentMonth().getIncome()),
                toDouble(comparison.getPreviousMonth().getIncome()));
        List<Double> expenses = List.of(toDouble(comparison.getCurrentMonth().getExpense()),
                toDouble(comparison.getPreviousMonth().getExpense()));
        List<Double> net = List.of(toDouble(comparison.getCurrentMonth().getNet()),
                toDouble(comparison.getPreviousMonth().getNet()));

        CategoryChart chart = new CategoryChartBuilder()
                .width(900).height(380)
                .title("Confronto mese")
                .xAxisTitle("Periodo")
                .yAxisTitle("Importo")
                .build();
        styleBarChart(chart, false);
        chart.addSeries("Entrate", labels, incomes);
        chart.addSeries("Uscite", labels, expenses);
        chart.addSeries("Netto", labels, net);
        return toDataUri(chart);
    }

    private String renderBalanceTrendChart(ReportDto.BalanceTrendResponse balanceTrend) {
        if (balanceTrend == null || balanceTrend.getItems() == null || balanceTrend.getItems().isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        for (ReportDto.BalanceTrendItem item : balanceTrend.getItems()) {
            labels.add(String.format("%02d/%d", item.getMonth(), item.getYear()));
            values.add(toDouble(item.getClosingBalance()));
        }

        CategoryChart chart = new CategoryChartBuilder()
                .width(900).height(420)
                .title("Andamento saldo")
                .xAxisTitle("Mese")
                .yAxisTitle("Saldo")
                .build();
        styleLineChart(chart);
        chart.addSeries("Saldo", labels, values);
        applyLineMarkers(chart);
        return toDataUri(chart);
    }

    private String renderProjectionChart(ReportDto.MonthlyProjection projection) {
        if (projection == null) {
            return null;
        }
        List<String> labels = List.of("Attuale", "Proiezione");
        List<Double> incomes = List.of(toDouble(projection.getCurrentMonthIncome()),
                toDouble(projection.getProjectedMonthlyIncome()));
        List<Double> expenses = List.of(toDouble(projection.getCurrentMonthExpense()),
                toDouble(projection.getProjectedMonthlyExpense()));

        CategoryChart chart = new CategoryChartBuilder()
                .width(900).height(380)
                .title("Proiezione mensile")
                .xAxisTitle("Periodo")
                .yAxisTitle("Importo")
                .build();
        styleBarChart(chart, false);
        chart.addSeries("Entrate", labels, incomes);
        chart.addSeries("Uscite", labels, expenses);
        return toDataUri(chart);
    }

    private void styleLineChart(CategoryChart chart) {
        CategoryStyler styler = chart.getStyler();
        styler.setDefaultSeriesRenderStyle(CategorySeries.CategorySeriesRenderStyle.Line);
        styler.setLegendPosition(LegendPosition.OutsideE);
        styler.setLegendLayout(LegendLayout.Vertical);
        styler.setMarkerSize(6);
        styler.setPlotGridLinesVisible(true);
        styler.setXAxisLabelRotation(45);
        styler.setYAxisDecimalPattern("#,##0.00");
    }

    private void styleBarChart(CategoryChart chart, boolean rotateLabels) {
        CategoryStyler styler = chart.getStyler();
        styler.setDefaultSeriesRenderStyle(CategorySeries.CategorySeriesRenderStyle.Bar);
        styler.setLegendPosition(LegendPosition.OutsideE);
        styler.setLegendLayout(LegendLayout.Vertical);
        styler.setPlotGridLinesVisible(true);
        styler.setXAxisLabelRotation(rotateLabels ? 45 : 0);
        styler.setYAxisDecimalPattern("#,##0.00");
    }

    private void applyLineMarkers(CategoryChart chart) {
        chart.getSeriesMap().values().forEach(series -> series.setMarker(SeriesMarkers.CIRCLE));
    }

    private String renderMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "<p>Nessun contenuto disponibile.</p>";
        }
        return htmlRenderer.render(markdownParser.parse(markdown));
    }

    private String toDataUri(CategoryChart chart) {
        try {
            byte[] pngBytes = BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
        } catch (Exception e) {
            log.warn("Errore rendering grafico PDF", e);
            return null;
        }
    }

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Errore durante la generazione del PDF", e);
        }
    }

    private double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    private String formatAmount(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatPercentage(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String shortLabel(String label, int maxLen) {
        if (label == null) {
            return "n/a";
        }
        String normalized = label.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
