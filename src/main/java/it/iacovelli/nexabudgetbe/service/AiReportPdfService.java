package it.iacovelli.nexabudgetbe.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import it.iacovelli.nexabudgetbe.dto.BudgetDto;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportPdfService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final int BAR_WIDTH = 20;

    // Initialized in @PostConstruct to avoid static-initializer execution at
    // GraalVM native-image build time, when FontFactory cannot load classpath fonts.
    private Font titleFont;
    private Font sectionFont;
    private Font subsectionFont;
    private Font textFont;
    private Font smallFont;
    private Font headerFont;

    private final ReportService reportService;
    private final BudgetService budgetService;

    @PostConstruct
    private void initFonts() {
        titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f);
        sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12f);
        subsectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f);
        textFont = FontFactory.getFont(FontFactory.HELVETICA, 10.5f);
        smallFont = FontFactory.getFont(FontFactory.HELVETICA, 9.5f);
        headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f);
    }

    public byte[] buildReportPdf(User user, LocalDate startDate, LocalDate endDate, String reportMarkdown) {
        ReportDto.MonthlyTrendResponse monthlyTrend = reportService.getMonthlyTrendByRange(user, startDate, endDate);
        ReportDto.CategoryBreakdownResponse categoryBreakdown = reportService.getCategoryBreakdown(user, startDate, endDate);
        ReportDto.MonthComparisonResponse monthComparison = reportService.getMonthComparison(user, endDate.getYear(), endDate.getMonthValue());
        ReportDto.BalanceTrendResponse balanceTrend = reportService.getBalanceTrend(user, startDate, endDate);
        ReportDto.MonthlyProjection projection = reportService.getMonthlyProjection(user);
        List<BudgetDto.MonthlySummaryResponse> budgetSummary = budgetService.getBudgetMonthlySummary(user, endDate);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            addTitle(document, user, startDate, endDate);
            addSectionTitle(document, "Report AI");
            addMarkdownContent(document, reportMarkdown);

            addSectionTitle(document, "Trend mensile");
            if (!addMonthlyTrendTable(document, monthlyTrend)) {
                addPlaceholder(document, "Nessun dato disponibile per questo grafico.");
            }

            addSectionTitle(document, "Breakdown per categoria");
            if (!addCategoryBreakdownTable(document, categoryBreakdown)) {
                addPlaceholder(document, "Nessun dato disponibile per questo grafico.");
            }

            addSectionTitle(document, "Confronto mese");
            if (!addMonthComparisonTable(document, monthComparison)) {
                addPlaceholder(document, "Nessun dato disponibile per questo grafico.");
            }

            addSectionTitle(document, "Andamento saldo");
            if (!addBalanceTrendTable(document, balanceTrend)) {
                addPlaceholder(document, "Nessun dato disponibile per questo grafico.");
            }

            addSectionTitle(document, "Proiezione mensile");
            if (!addProjectionTable(document, projection)) {
                addPlaceholder(document, "Nessun dato disponibile per questo grafico.");
            }

            addSectionTitle(document, "Budget mensili");
            if (!addBudgetSummaryTable(document, budgetSummary)) {
                addPlaceholder(document, "Nessun budget attivo nel mese selezionato.");
            }

            addFooter(document);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Errore durante la generazione del PDF", e);
        }
    }

    public String buildFilename(LocalDate startDate, LocalDate endDate) {
        return "report_finanziario_" + startDate.format(FILE_FORMAT) + "_" + endDate.format(FILE_FORMAT) + ".pdf";
    }

    private void addTitle(Document document, User user, LocalDate startDate, LocalDate endDate) throws DocumentException {
        Paragraph title = new Paragraph("Report finanziario nexaBudget", titleFont);
        title.setSpacingAfter(6f);
        document.add(title);

        String username = user != null && user.getUsername() != null ? user.getUsername() : "";
        Paragraph userLine = new Paragraph("Utente: " + username, textFont);
        userLine.setSpacingAfter(2f);
        document.add(userLine);

        Paragraph periodLine = new Paragraph("Periodo: " + startDate.format(DATE_FORMAT) + " - " + endDate.format(DATE_FORMAT), textFont);
        periodLine.setSpacingAfter(10f);
        document.add(periodLine);
    }

    private void addSectionTitle(Document document, String title) throws DocumentException {
        Paragraph section = new Paragraph(title, sectionFont);
        section.setSpacingBefore(8f);
        section.setSpacingAfter(4f);
        document.add(section);
    }

    private void addFooter(Document document) throws DocumentException {
        Paragraph footer = new Paragraph("Generato il " + LocalDate.now().format(DATE_FORMAT), smallFont);
        footer.setSpacingBefore(12f);
        document.add(footer);
    }

    private void addPlaceholder(Document document, String text) throws DocumentException {
        Paragraph placeholder = new Paragraph(text, textFont);
        placeholder.setSpacingAfter(6f);
        document.add(placeholder);
    }

    private void addMarkdownContent(Document document, String markdown) throws DocumentException {
        if (markdown == null || markdown.isBlank()) {
            addPlaceholder(document, "Nessun contenuto disponibile.");
            return;
        }

        String[] lines = markdown.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                Paragraph spacer = new Paragraph(" ", textFont);
                spacer.setSpacingAfter(2f);
                document.add(spacer);
                continue;
            }
            if (trimmed.startsWith("###")) {
                addMarkdownHeading(document, trimmed.substring(3));
            } else if (trimmed.startsWith("##")) {
                addMarkdownHeading(document, trimmed.substring(2));
            } else if (trimmed.startsWith("#")) {
                addMarkdownHeading(document, trimmed.substring(1));
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String text = "- " + trimmed.substring(2).trim();
                Paragraph bullet = new Paragraph(text, textFont);
                bullet.setSpacingAfter(2f);
                document.add(bullet);
            } else {
                Paragraph paragraph = new Paragraph(trimmed, textFont);
                paragraph.setSpacingAfter(2f);
                document.add(paragraph);
            }
        }
    }

    private void addMarkdownHeading(Document document, String heading) throws DocumentException {
        String text = heading.trim();
        if (!text.isEmpty()) {
            Paragraph title = new Paragraph(text, subsectionFont);
            title.setSpacingBefore(4f);
            title.setSpacingAfter(2f);
            document.add(title);
        }
    }

    private boolean addMonthlyTrendTable(Document document, ReportDto.MonthlyTrendResponse monthlyTrend) throws DocumentException {
        if (monthlyTrend == null || monthlyTrend.getItems() == null || monthlyTrend.getItems().isEmpty()) {
            return false;
        }
        double maxIncome = 0.0;
        double maxExpense = 0.0;
        double maxNet = 0.0;

        for (ReportDto.MonthlyTrendItem item : monthlyTrend.getItems()) {
            maxIncome = Math.max(maxIncome, Math.abs(toDouble(item.getIncome())));
            maxExpense = Math.max(maxExpense, Math.abs(toDouble(item.getExpense())));
            maxNet = Math.max(maxNet, Math.abs(toDouble(item.getNet())));
        }

        PdfPTable table = createTable(new float[] {1.2f, 2.2f, 2.2f, 2.2f});
        addHeaderRow(table, "Mese", "Entrate", "Uscite", "Netto");

        for (ReportDto.MonthlyTrendItem item : monthlyTrend.getItems()) {
            String label = String.format("%02d/%d", item.getMonth(), item.getYear());
            table.addCell(cell(label));
            table.addCell(cell(barValue(item.getIncome(), maxIncome, false)));
            table.addCell(cell(barValue(item.getExpense(), maxExpense, false)));
            table.addCell(cell(barValue(item.getNet(), maxNet, true)));
        }

        document.add(table);
        return true;
    }

    private boolean addCategoryBreakdownTable(Document document, ReportDto.CategoryBreakdownResponse breakdown) throws DocumentException {
        if (breakdown == null || breakdown.getCategories() == null || breakdown.getCategories().isEmpty()) {
            return false;
        }
        double maxValue = 0.0;
        for (ReportDto.CategoryBreakdownItem item : breakdown.getCategories()) {
            maxValue = Math.max(maxValue, Math.abs(toDouble(item.getNet())));
        }

        PdfPTable table = createTable(new float[] {2.4f, 1.0f, 2.6f});
        addHeaderRow(table, "Categoria", "Tipo", "Valore");

        for (ReportDto.CategoryBreakdownItem item : breakdown.getCategories()) {
            String typeLabel = item.getInferredType() == TransactionType.OUT ? "Uscite" : "Entrate";
            table.addCell(cell(shortLabel(item.getCategoryName(), 30)));
            table.addCell(cell(typeLabel));
            table.addCell(cell(barValue(item.getNet(), maxValue, false)));
        }

        document.add(table);
        return true;
    }

    private boolean addMonthComparisonTable(Document document, ReportDto.MonthComparisonResponse comparison) throws DocumentException {
        if (comparison == null || comparison.getCurrentMonth() == null || comparison.getPreviousMonth() == null) {
            return false;
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

        PdfPTable table = createTable(new float[] {1.6f, 2.2f, 2.2f});
        addHeaderRow(table, "Metrica", "Mese corrente", "Mese precedente");

        table.addCell(cell("Entrate"));
        table.addCell(cell(barValue(currIncome, maxIncome, false)));
        table.addCell(cell(barValue(prevIncome, maxIncome, false)));

        table.addCell(cell("Uscite"));
        table.addCell(cell(barValue(currExpense, maxExpense, false)));
        table.addCell(cell(barValue(prevExpense, maxExpense, false)));

        table.addCell(cell("Netto"));
        table.addCell(cell(barValue(currNet, maxNet, true)));
        table.addCell(cell(barValue(prevNet, maxNet, true)));

        document.add(table);
        return true;
    }

    private boolean addBalanceTrendTable(Document document, ReportDto.BalanceTrendResponse balanceTrend) throws DocumentException {
        if (balanceTrend == null || balanceTrend.getItems() == null || balanceTrend.getItems().isEmpty()) {
            return false;
        }
        double maxValue = 0.0;
        for (ReportDto.BalanceTrendItem item : balanceTrend.getItems()) {
            maxValue = Math.max(maxValue, Math.abs(toDouble(item.getClosingBalance())));
        }

        PdfPTable table = createTable(new float[] {1.2f, 3.2f});
        addHeaderRow(table, "Mese", "Saldo");

        for (ReportDto.BalanceTrendItem item : balanceTrend.getItems()) {
            String label = String.format("%02d/%d", item.getMonth(), item.getYear());
            table.addCell(cell(label));
            table.addCell(cell(barValue(item.getClosingBalance(), maxValue, true)));
        }

        document.add(table);
        return true;
    }

    private boolean addProjectionTable(Document document, ReportDto.MonthlyProjection projection) throws DocumentException {
        if (projection == null) {
            return false;
        }
        double currIncome = toDouble(projection.getCurrentMonthIncome());
        double projIncome = toDouble(projection.getProjectedMonthlyIncome());
        double currExpense = toDouble(projection.getCurrentMonthExpense());
        double projExpense = toDouble(projection.getProjectedMonthlyExpense());

        double maxIncome = Math.max(Math.abs(currIncome), Math.abs(projIncome));
        double maxExpense = Math.max(Math.abs(currExpense), Math.abs(projExpense));

        PdfPTable table = createTable(new float[] {1.6f, 2.2f, 2.2f});
        addHeaderRow(table, "Metrica", "Attuale", "Proiezione");

        table.addCell(cell("Entrate"));
        table.addCell(cell(barValue(currIncome, maxIncome, false)));
        table.addCell(cell(barValue(projIncome, maxIncome, false)));

        table.addCell(cell("Uscite"));
        table.addCell(cell(barValue(currExpense, maxExpense, false)));
        table.addCell(cell(barValue(projExpense, maxExpense, false)));

        document.add(table);
        return true;
    }

    private boolean addBudgetSummaryTable(Document document, List<BudgetDto.MonthlySummaryResponse> budgetSummary) throws DocumentException {
        if (budgetSummary == null || budgetSummary.isEmpty()) {
            return false;
        }

        PdfPTable table = createTable(new float[] {2.0f, 1.2f, 1.2f, 1.2f, 1.0f, 1.2f});
        addHeaderRow(table, "Categoria", "Limite", "Speso", "Residuo", "% Utilizzo", "Periodo");

        for (BudgetDto.MonthlySummaryResponse item : budgetSummary) {
            table.addCell(cell(item.getCategoryName()));
            table.addCell(cell(formatAmount(item.getLimit())));
            table.addCell(cell(formatAmount(item.getSpent())));
            table.addCell(cell(formatAmount(item.getRemaining())));
            table.addCell(cell(formatPercentage(item.getPercentageUsed())));
            table.addCell(cell(item.getPeriodStart().format(MONTH_FORMAT)));
        }

        document.add(table);
        return true;
    }

    private PdfPTable createTable(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100f);
        table.setSpacingAfter(6f);
        return table;
    }

    private void addHeaderRow(PdfPTable table, String... headers) {
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            cell.setPadding(4f);
            table.addCell(cell);
        }
    }

    private PdfPCell cell(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value != null ? value : "", smallFont));
        cell.setPadding(4f);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        return cell;
    }

    private String barValue(BigDecimal value, double max, boolean signed) {
        return barValue(toDouble(value), max, signed);
    }

    private String barValue(double value, double max, boolean signed) {
        String bar = buildBar(value, max);
        String amount = signed ? formatSignedAmount(value) : formatAmount(Math.abs(value));
        if (bar.isEmpty()) {
            return amount;
        }
        return bar + " " + amount;
    }

    private String buildBar(double value, double max) {
        if (max <= 0.0) {
            return "";
        }
        int len = (int) Math.round(Math.abs(value) / max * BAR_WIDTH);
        len = Math.max(0, Math.min(BAR_WIDTH, len));
        return "#".repeat(len);
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

    private String formatAmount(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
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
