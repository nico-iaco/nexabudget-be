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
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

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
        String monthlyTrendChart = safeChart("trend mensile", () -> renderMonthlyTrendChart(monthlyTrend));
        String categoryBreakdownChart = safeChart("breakdown categoria", () -> renderCategoryBreakdownChart(categoryBreakdown));
        String monthComparisonChart = safeChart("confronto mese", () -> renderMonthComparisonChart(monthComparison));
        String balanceTrendChart = safeChart("andamento saldo", () -> renderBalanceTrendChart(balanceTrend));
        String projectionChart = safeChart("proiezione mensile", () -> renderProjectionChart(projection));

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
          .append(".chart-placeholder { color: #777; font-style: italic; }")
          .append(".chart-table { width: 100%; border-collapse: collapse; margin-top: 6px; }")
          .append(".chart-table th, .chart-table td { border: 1px solid #e5e5e5; padding: 4px; text-align: left; font-size: 10.5px; }")
          .append(".chart-table th { background: #f5f5f5; }")
          .append(".bar-bg { background: #f0f0f0; height: 10px; border-radius: 2px; }")
          .append(".bar { height: 10px; border-radius: 2px; }")
          .append(".bar-in { background: #43a047; }")
          .append(".bar-out { background: #e53935; }")
          .append(".bar-net { background: #1e88e5; }")
          .append(".bar-balance { background: #3949ab; }")
          .append(".bar-proj { background: #6d4c41; }")
          .append(".bar-value { font-size: 10px; color: #444; margin-top: 2px; }")
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

    private void appendChartSection(StringBuilder sb, String title, String subtitle, String chartHtml) {
        sb.append("<div class=\"section\"><h2>")
          .append(HtmlUtils.htmlEscape(title))
          .append("</h2>")
          .append("<div class=\"meta\">")
          .append(HtmlUtils.htmlEscape(subtitle))
          .append("</div>");

        if (chartHtml == null) {
            sb.append("<p class=\"chart-placeholder\">Nessun dato disponibile per questo grafico.</p>");
        } else {
            sb.append(chartHtml);
        }
        sb.append("</div>");
    }

    private String renderMonthlyTrendChart(ReportDto.MonthlyTrendResponse monthlyTrend) {
        if (monthlyTrend == null || monthlyTrend.getItems() == null || monthlyTrend.getItems().isEmpty()) {
            return null;
        }
        double maxIncome = 0.0;
        double maxExpense = 0.0;
        double maxNet = 0.0;

        for (ReportDto.MonthlyTrendItem item : monthlyTrend.getItems()) {
            maxIncome = Math.max(maxIncome, Math.abs(toDouble(item.getIncome())));
            maxExpense = Math.max(maxExpense, Math.abs(toDouble(item.getExpense())));
            maxNet = Math.max(maxNet, Math.abs(toDouble(item.getNet())));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"chart-table\"><thead><tr>")
          .append("<th>Mese</th><th>Entrate</th><th>Uscite</th><th>Netto</th>")
          .append("</tr></thead><tbody>");

        for (ReportDto.MonthlyTrendItem item : monthlyTrend.getItems()) {
            String label = String.format("%02d/%d", item.getMonth(), item.getYear());
            sb.append("<tr>")
              .append("<td>").append(label).append("</td>")
              .append("<td>").append(buildBarCell(toDouble(item.getIncome()), maxIncome, "bar-in", false)).append("</td>")
              .append("<td>").append(buildBarCell(toDouble(item.getExpense()), maxExpense, "bar-out", false)).append("</td>")
              .append("<td>").append(buildBarCell(toDouble(item.getNet()), maxNet, "bar-net", true)).append("</td>")
              .append("</tr>");
        }

        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String renderCategoryBreakdownChart(ReportDto.CategoryBreakdownResponse breakdown) {
        if (breakdown == null || breakdown.getCategories() == null || breakdown.getCategories().isEmpty()) {
            return null;
        }
        double maxValue = 0.0;
        for (ReportDto.CategoryBreakdownItem item : breakdown.getCategories()) {
            maxValue = Math.max(maxValue, Math.abs(toDouble(item.getNet())));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"chart-table\"><thead><tr>")
          .append("<th>Categoria</th><th>Tipo</th><th>Valore</th>")
          .append("</tr></thead><tbody>");

        for (ReportDto.CategoryBreakdownItem item : breakdown.getCategories()) {
            String typeLabel = item.getInferredType() == TransactionType.OUT ? "Uscite" : "Entrate";
            String cssClass = item.getInferredType() == TransactionType.OUT ? "bar-out" : "bar-in";
            sb.append("<tr>")
              .append("<td>").append(HtmlUtils.htmlEscape(shortLabel(item.getCategoryName(), 30))).append("</td>")
              .append("<td>").append(typeLabel).append("</td>")
              .append("<td>").append(buildBarCell(toDouble(item.getNet()), maxValue, cssClass, false)).append("</td>")
              .append("</tr>");
        }

        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String renderMonthComparisonChart(ReportDto.MonthComparisonResponse comparison) {
        if (comparison == null || comparison.getCurrentMonth() == null || comparison.getPreviousMonth() == null) {
            return null;
        }
        double currIncome = toDouble(comparison.getCurrentMonth().getIncome());
        double prevIncome = toDouble(comparison.getPreviousMonth().getIncome());
        double currExpense = toDouble(comparison.getCurrentMonth().getExpense());
        double prevExpense = toDouble(comparison.getPreviousMonth().getExpense());
        double currNet = toDouble(comparison.getCurrentMonth().getNet());
        double prevNet = toDouble(comparison.getPreviousMonth().getNet());

        double maxIncome = Math.max(Math.abs(currIncome), Math.abs(prevIncome));
        double maxExpense = Math.max(Math.abs(currExpense), Math.abs(prevExpense));
        double maxNet = Math.max(Math.abs(currNet), Math.abs(prevNet));

        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"chart-table\"><thead><tr>")
          .append("<th>Metrica</th><th>Mese corrente</th><th>Mese precedente</th>")
          .append("</tr></thead><tbody>");

        sb.append("<tr><td>Entrate</td>")
          .append("<td>").append(buildBarCell(currIncome, maxIncome, "bar-in", false)).append("</td>")
          .append("<td>").append(buildBarCell(prevIncome, maxIncome, "bar-in", false)).append("</td>")
          .append("</tr>");

        sb.append("<tr><td>Uscite</td>")
          .append("<td>").append(buildBarCell(currExpense, maxExpense, "bar-out", false)).append("</td>")
          .append("<td>").append(buildBarCell(prevExpense, maxExpense, "bar-out", false)).append("</td>")
          .append("</tr>");

        sb.append("<tr><td>Netto</td>")
          .append("<td>").append(buildBarCell(currNet, maxNet, "bar-net", true)).append("</td>")
          .append("<td>").append(buildBarCell(prevNet, maxNet, "bar-net", true)).append("</td>")
          .append("</tr>");

        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String renderBalanceTrendChart(ReportDto.BalanceTrendResponse balanceTrend) {
        if (balanceTrend == null || balanceTrend.getItems() == null || balanceTrend.getItems().isEmpty()) {
            return null;
        }
        double maxValue = 0.0;
        for (ReportDto.BalanceTrendItem item : balanceTrend.getItems()) {
            maxValue = Math.max(maxValue, Math.abs(toDouble(item.getClosingBalance())));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"chart-table\"><thead><tr>")
          .append("<th>Mese</th><th>Saldo</th>")
          .append("</tr></thead><tbody>");

        for (ReportDto.BalanceTrendItem item : balanceTrend.getItems()) {
            String label = String.format("%02d/%d", item.getMonth(), item.getYear());
            sb.append("<tr>")
              .append("<td>").append(label).append("</td>")
              .append("<td>").append(buildBarCell(toDouble(item.getClosingBalance()), maxValue, "bar-balance", true)).append("</td>")
              .append("</tr>");
        }

        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String renderProjectionChart(ReportDto.MonthlyProjection projection) {
        if (projection == null) {
            return null;
        }
                double currIncome = toDouble(projection.getCurrentMonthIncome());
                double projIncome = toDouble(projection.getProjectedMonthlyIncome());
                double currExpense = toDouble(projection.getCurrentMonthExpense());
                double projExpense = toDouble(projection.getProjectedMonthlyExpense());

                double maxIncome = Math.max(Math.abs(currIncome), Math.abs(projIncome));
                double maxExpense = Math.max(Math.abs(currExpense), Math.abs(projExpense));

                StringBuilder sb = new StringBuilder();
                sb.append("<table class=\"chart-table\"><thead><tr>")
                    .append("<th>Metrica</th><th>Attuale</th><th>Proiezione</th>")
                    .append("</tr></thead><tbody>");

                sb.append("<tr><td>Entrate</td>")
                    .append("<td>").append(buildBarCell(currIncome, maxIncome, "bar-in", false)).append("</td>")
                    .append("<td>").append(buildBarCell(projIncome, maxIncome, "bar-in", false)).append("</td>")
                    .append("</tr>");

                sb.append("<tr><td>Uscite</td>")
                    .append("<td>").append(buildBarCell(currExpense, maxExpense, "bar-out", false)).append("</td>")
                    .append("<td>").append(buildBarCell(projExpense, maxExpense, "bar-out", false)).append("</td>")
                    .append("</tr>");

                sb.append("</tbody></table>");
                return sb.toString();
        }

    private String renderMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "<p>Nessun contenuto disponibile.</p>";
        }
        return htmlRenderer.render(markdownParser.parse(markdown));
    }

    private String buildBarCell(double value, double max, String cssClass, boolean signed) {
        double width = max <= 0.0 ? 0.0 : Math.min(100.0, Math.abs(value) / max * 100.0);
        String display = signed ? formatSignedAmount(value) : formatAmount(Math.abs(value));
        return "<div class=\"bar-bg\"><div class=\"bar " + cssClass + "\" style=\"width:"
                + formatPercent(width) + "%\"></div></div><div class=\"bar-value\">"
                + display + "</div>";
    }

    private String formatSignedAmount(double value) {
        if (value > 0) {
            return "+" + formatAmount(value);
        }
        if (value < 0) {
            return "-" + formatAmount(Math.abs(value));
        }
        return "0.00";
    }

    private String formatAmount(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatPercent(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private String safeChart(String name, Supplier<String> renderer) {
        try {
            return renderer.get();
        } catch (Throwable t) {
            log.warn("Rendering grafico '{}' fallito. Continuiamo senza grafico.", name, t);
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
