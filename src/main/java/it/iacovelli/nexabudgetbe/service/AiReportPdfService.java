package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetDto;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.ListItem;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.draw.LineSeparator;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportPdfService {

    private static final Locale LOCALE = Locale.ITALY;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ─── Palette ─────────────────────────────────────────────────────────────
    private static final Color PRIMARY = new Color(79, 70, 229);
    private static final Color PRIMARY_DARK = new Color(55, 48, 163);
    private static final Color PRIMARY_SOFT = new Color(199, 210, 254);
    private static final Color INK = new Color(15, 23, 42);
    private static final Color TEXT = new Color(51, 65, 85);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(226, 232, 240);
    private static final Color SURFACE = new Color(248, 250, 252);
    private static final Color TRACK = new Color(237, 242, 247);
    private static final Color INCOME = new Color(16, 185, 129);
    private static final Color EXPENSE = new Color(239, 68, 68);
    private static final Color BALANCE = new Color(139, 92, 246);
    private static final Color WARNING = new Color(245, 158, 11);

    // ─── Page geometry (A4: 595 x 842 pt) ────────────────────────────────────
    private static final float MARGIN_X = 40f;
    private static final float MARGIN_TOP = 48f;
    private static final float MARGIN_BOTTOM = 56f;

    private static final float CHART_HEIGHT = 190f;
    private static final float BAR_HEIGHT = 7f;
    private static final float PLACEHOLDER_HEIGHT = 50f;
    private static final float ROW_HEIGHT = 24f;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern BOLD_ONLY_LINE = Pattern.compile("^\\*\\*([^*]+)\\*\\*:?$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");
    private static final Pattern NUMERIC_CELL = Pattern.compile("^[-+]?[\\d.,\\s€$£%]+$");
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![\\w*])\\*(?![\\s*])(.+?)(?<!\\s)\\*(?![\\w*])|`([^`]+)`");

    // Initialized in @PostConstruct to avoid static-initializer execution at
    // GraalVM native-image build time, when BaseFont cannot load classpath font metrics.
    private BaseFont regular;
    private BaseFont bold;
    private BaseFont italic;

    private final ReportService reportService;
    private final BudgetService budgetService;

    @PostConstruct
    void initFonts() {
        try {
            regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            italic = BaseFont.createFont(BaseFont.HELVETICA_OBLIQUE, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Impossibile caricare i font del report PDF", e);
        }
    }

    /** Rendering state for a single PDF: never shared across requests. */
    private record Ctx(Document document, PdfWriter writer, String currencySymbol) {
        float contentWidth() {
            return document.right() - document.left();
        }
    }

    public byte[] buildReportPdf(User user, LocalDate startDate, LocalDate endDate, String reportMarkdown) {
        ReportDto.MonthlyTrendResponse monthlyTrend = reportService.getMonthlyTrendByRange(user, startDate, endDate);
        ReportDto.CategoryBreakdownResponse categoryBreakdown = reportService.getCategoryBreakdown(user, startDate, endDate);
        ReportDto.MonthComparisonResponse monthComparison = reportService.getMonthComparison(user, endDate.getYear(), endDate.getMonthValue());
        ReportDto.BalanceTrendResponse balanceTrend = reportService.getBalanceTrend(user, startDate, endDate);
        ReportDto.MonthlyProjection projection = reportService.getMonthlyProjection(user);
        List<BudgetDto.MonthlySummaryResponse> budgetSummary = budgetService.getBudgetMonthlySummary(user, endDate);

        String currencyCode = monthlyTrend != null && monthlyTrend.getCurrency() != null
                ? monthlyTrend.getCurrency()
                : user != null && user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR";

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, MARGIN_X, MARGIN_X, MARGIN_TOP, MARGIN_BOTTOM);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setStrictImageSequence(true);
            writer.setPageEvent(new PageDecorator(startDate, endDate));
            document.addTitle("Report finanziario nexaBudget");
            document.addAuthor("nexaBudget");
            document.addCreator("nexaBudget");
            document.open();

            Ctx ctx = new Ctx(document, writer, currencySymbol(currencyCode));
            int section = 1;

            addHeader(ctx, user, startDate, endDate);
            addKpiCards(ctx, monthlyTrend);

            addSectionHeading(ctx, section++, "Analisi AI", "Sintesi e consigli generati dall'assistente sui dati del periodo", 60f);
            addMarkdownContent(ctx, reportMarkdown);

            addSectionHeading(ctx, section++, "Trend mensile", "Entrate e uscite lorde mese per mese",
                    hasItems(monthlyTrend != null ? monthlyTrend.getItems() : null) ? CHART_HEIGHT + 40f : PLACEHOLDER_HEIGHT);
            if (!addMonthlyTrend(ctx, monthlyTrend)) {
                addPlaceholder(ctx, "Nessun movimento registrato nel periodo.");
            }

            addSectionHeading(ctx, section++, "Spese ed entrate per categoria", "Valori netti per categoria (uscite meno rimborsi)",
                    hasItems(categoryBreakdown != null ? categoryBreakdown.getCategories() : null) ? 90f : PLACEHOLDER_HEIGHT);
            if (!addCategoryBreakdown(ctx, categoryBreakdown)) {
                addPlaceholder(ctx, "Nessuna transazione categorizzata nel periodo.");
            }

            addSectionHeading(ctx, section++, "Confronto con il mese precedente", null, monthComparison != null ? 110f : PLACEHOLDER_HEIGHT);
            if (!addMonthComparison(ctx, monthComparison)) {
                addPlaceholder(ctx, "Dati insufficienti per il confronto.");
            }

            addSectionHeading(ctx, section++, "Andamento del saldo", "Saldo complessivo a fine mese",
                    hasItems(balanceTrend != null ? balanceTrend.getItems() : null) ? CHART_HEIGHT + 10f : PLACEHOLDER_HEIGHT);
            if (!addBalanceTrend(ctx, balanceTrend)) {
                addPlaceholder(ctx, "Nessun dato di saldo disponibile.");
            }

            addSectionHeading(ctx, section++, "Proiezione di fine mese", projectionSubtitle(projection),
                    projection != null ? 110f : PLACEHOLDER_HEIGHT);
            if (!addProjection(ctx, projection)) {
                addPlaceholder(ctx, "Proiezione non disponibile.");
            }

            addSectionHeading(ctx, section, "Budget del mese", null,
                    hasItems(budgetSummary) ? Math.min(60f + budgetSummary.size() * 30f, 300f) : PLACEHOLDER_HEIGHT);
            if (!addBudgetSummary(ctx, budgetSummary)) {
                addPlaceholder(ctx, "Nessun budget attivo nel mese selezionato.");
            }

            addDisclaimer(ctx);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Errore durante la generazione del PDF", e);
        }
    }

    public String buildFilename(LocalDate startDate, LocalDate endDate) {
        return "report_finanziario_" + startDate.format(FILE_FORMAT) + "_" + endDate.format(FILE_FORMAT) + ".pdf";
    }

    // ─── Header & KPI ────────────────────────────────────────────────────────

    private void addHeader(Ctx ctx, User user, LocalDate startDate, LocalDate endDate) throws DocumentException {
        PdfPTable band = new PdfPTable(new float[] {3f, 1.4f});
        band.setWidthPercentage(100f);
        band.setSpacingAfter(14f);

        Paragraph left = new Paragraph();
        Chunk brand = new Chunk("NEXABUDGET", font(bold, 8.5f, PRIMARY_SOFT));
        brand.setCharacterSpacing(1.6f);
        left.add(brand);
        left.add(Chunk.NEWLINE);
        left.add(new Chunk("Report finanziario", font(bold, 22f, Color.WHITE)));
        left.add(Chunk.NEWLINE);
        left.add(new Chunk(startDate.format(DATE_FORMAT) + "  –  " + endDate.format(DATE_FORMAT), font(regular, 10.5f, PRIMARY_SOFT)));
        left.setLeading(0f, 1.45f);

        PdfPCell leftCell = new PdfPCell();
        leftCell.addElement(left);
        styleBand(leftCell);
        leftCell.setPaddingLeft(20f);
        band.addCell(leftCell);

        String username = user != null && user.getUsername() != null ? clean(user.getUsername()) : "";
        Paragraph right = new Paragraph();
        right.setAlignment(Element.ALIGN_RIGHT);
        right.setLeading(0f, 1.45f);
        right.add(new Chunk("Preparato per", font(regular, 8f, PRIMARY_SOFT)));
        right.add(Chunk.NEWLINE);
        right.add(new Chunk(username, font(bold, 11f, Color.WHITE)));
        right.add(Chunk.NEWLINE);
        right.add(new Chunk("Generato il " + LocalDate.now().format(DATE_FORMAT), font(regular, 8f, PRIMARY_SOFT)));

        PdfPCell rightCell = new PdfPCell();
        rightCell.addElement(right);
        styleBand(rightCell);
        rightCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        rightCell.setPaddingRight(20f);
        band.addCell(rightCell);

        band.setTableEvent((table, widths, heights, headerRows, rowStart, canvases) -> {
            PdfContentByte cb = canvases[PdfPTable.BASECANVAS];
            float x = widths[0][0];
            float w = widths[0][widths[0].length - 1] - x;
            float yTop = heights[0];
            float yBottom = heights[heights.length - 1];
            cb.saveState();
            cb.setColorFill(PRIMARY);
            cb.roundRectangle(x, yBottom, w, yTop - yBottom, 10f);
            cb.fill();
            // Decorative circles in the top-right corner.
            PdfGState translucent = new PdfGState();
            translucent.setFillOpacity(0.08f);
            cb.setGState(translucent);
            cb.setColorFill(Color.WHITE);
            cb.circle(x + w - 40f, yTop - 10f, 46f);
            cb.fill();
            cb.circle(x + w - 120f, yTop + 6f, 26f);
            cb.fill();
            cb.restoreState();
        });

        ctx.document().add(band);
    }

    private void styleBand(PdfPCell cell) {
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(14f);
        cell.setPaddingBottom(18f);
    }

    private void addKpiCards(Ctx ctx, ReportDto.MonthlyTrendResponse trend) throws DocumentException {
        double income = 0;
        double expense = 0;
        int months = 0;
        if (trend != null && trend.getItems() != null) {
            for (ReportDto.MonthlyTrendItem item : trend.getItems()) {
                income += toDouble(item.getIncome());
                expense += toDouble(item.getExpense());
                months++;
            }
        }
        double net = income - expense;

        PdfPTable cards = new PdfPTable(4);
        cards.setWidthPercentage(100f);
        cards.setSpacingAfter(6f);

        String monthsLabel = months == 1 ? "1 mese" : months + " mesi";
        cards.addCell(kpiCard("Entrate", money(ctx, income), "nel periodo", INCOME));
        cards.addCell(kpiCard("Uscite", money(ctx, expense),
                months > 0 ? "media " + money(ctx, expense / months) + "/mese" : "nel periodo", EXPENSE));
        cards.addCell(kpiCard("Saldo netto", signedMoney(ctx, net), monthsLabel, net >= 0 ? INCOME : EXPENSE));

        if (income > 0) {
            double rate = net / income * 100;
            Color color = rate >= 20 ? INCOME : rate >= 0 ? WARNING : EXPENSE;
            cards.addCell(kpiCard("Tasso di risparmio", percent(rate), "delle entrate", color));
        } else {
            cards.addCell(kpiCard("Tasso di risparmio", "n/d", "nessuna entrata", MUTED));
        }

        ctx.document().add(cards);
    }

    private PdfPCell kpiCard(String label, String value, String caption, Color accent) {
        Paragraph content = new Paragraph();
        content.setLeading(0f, 1.5f);
        Chunk labelChunk = new Chunk(label.toUpperCase(LOCALE), font(bold, 7.5f, MUTED));
        labelChunk.setCharacterSpacing(0.6f);
        content.add(labelChunk);
        content.add(Chunk.NEWLINE);
        boolean zero = value.matches("^[+-]?0,00 .*") || accent == MUTED;
        content.add(new Chunk(value, font(bold, 14f, zero ? INK : accent)));
        content.add(Chunk.NEWLINE);
        content.add(new Chunk(caption, font(regular, 8f, MUTED)));

        PdfPCell cell = new PdfPCell();
        cell.addElement(content);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(12f);
        cell.setPaddingBottom(12f);
        cell.setPaddingLeft(14f);
        cell.setPaddingRight(8f);
        cell.setCellEvent(new CardBackground(accent));
        return cell;
    }

    // ─── Section scaffolding ─────────────────────────────────────────────────

    private void addSectionHeading(Ctx ctx, int number, String title, String subtitle, float minSpaceBelow) throws DocumentException {
        ensureSpace(ctx, 54f + minSpaceBelow);

        PdfPTable heading = new PdfPTable(new float[] {26f, ctx.contentWidth() - 26f});
        heading.setWidthPercentage(100f);
        heading.setSpacingBefore(16f);
        heading.setSpacingAfter(subtitle != null ? 6f : 8f);

        PdfPCell badge = new PdfPCell(new Phrase(String.format("%02d", number), font(bold, 9f, Color.WHITE)));
        badge.setBorder(Rectangle.NO_BORDER);
        badge.setHorizontalAlignment(Element.ALIGN_CENTER);
        badge.setVerticalAlignment(Element.ALIGN_MIDDLE);
        badge.setFixedHeight(20f);
        badge.setPaddingTop(1f);
        badge.setCellEvent(new RoundedFill(PRIMARY, 5f, 0f));
        heading.addCell(badge);

        PdfPCell titleCell = new PdfPCell(new Phrase(title, font(bold, 14f, INK)));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleCell.setPaddingLeft(10f);
        titleCell.setPaddingBottom(4f);
        heading.addCell(titleCell);

        if (subtitle != null) {
            PdfPCell spacer = new PdfPCell();
            spacer.setBorder(Rectangle.NO_BORDER);
            heading.addCell(spacer);
            PdfPCell subtitleCell = new PdfPCell(new Phrase(subtitle, font(regular, 9f, MUTED)));
            subtitleCell.setBorder(Rectangle.NO_BORDER);
            subtitleCell.setPaddingLeft(10f);
            subtitleCell.setPaddingTop(0f);
            heading.addCell(subtitleCell);
        }

        ctx.document().add(heading);
    }

    private void ensureSpace(Ctx ctx, float needed) {
        if (ctx.writer().getVerticalPosition(false) - needed < ctx.document().bottom()) {
            ctx.document().newPage();
        }
    }

    private void addPlaceholder(Ctx ctx, String text) throws DocumentException {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100f);
        box.setSpacingAfter(4f);
        PdfPCell cell = new PdfPCell(new Phrase(text, font(italic, 9.5f, MUTED)));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(12f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setCellEvent(new RoundedFill(SURFACE, 6f, 0f));
        box.addCell(cell);
        ctx.document().add(box);
    }

    private void addSubheading(Ctx ctx, String text, Color accent) throws DocumentException {
        PdfPTable heading = new PdfPTable(new float[] {3f, ctx.contentWidth() - 3f});
        heading.setWidthPercentage(100f);
        heading.setSpacingBefore(6f);
        PdfPCell marker = new PdfPCell();
        marker.setBorder(Rectangle.NO_BORDER);
        marker.setFixedHeight(13f);
        marker.setCellEvent(new RoundedFill(accent, 1.5f, 0f));
        heading.addCell(marker);
        PdfPCell label = new PdfPCell(new Phrase(text, font(bold, 10f, INK)));
        label.setBorder(Rectangle.NO_BORDER);
        label.setVerticalAlignment(Element.ALIGN_MIDDLE);
        label.setPaddingLeft(7f);
        label.setPaddingTop(0f);
        label.setPaddingBottom(3f);
        heading.addCell(label);
        ctx.document().add(heading);
    }

    private void addDisclaimer(Ctx ctx) throws DocumentException {
        ensureSpace(ctx, 50f);
        ctx.document().add(new LineSeparator(0.5f, 100f, BORDER, Element.ALIGN_CENTER, -8f));
        Paragraph p = new Paragraph(
                "Report generato automaticamente da nexaBudget con il supporto dell'intelligenza artificiale. "
                        + "I commenti dell'analisi AI vanno verificati sui dati prima di prendere decisioni finanziarie.",
                font(italic, 8f, MUTED));
        p.setSpacingBefore(14f);
        p.setLeading(0f, 1.4f);
        ctx.document().add(p);
    }

    // ─── Markdown (AI analysis) ──────────────────────────────────────────────

    private void addMarkdownContent(Ctx ctx, String markdown) throws DocumentException {
        if (markdown == null || markdown.isBlank()) {
            addPlaceholder(ctx, "Nessun contenuto disponibile.");
            return;
        }

        String[] lines = markdown.split("\\r?\\n");
        boolean contentSeen = false;
        for (int i = 0; i < lines.length; i++) {
            String line = clean(lines[i]);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean firstContent = !contentSeen;
            contentSeen = true;

            if (trimmed.startsWith("|")) {
                List<String> tableLines = new ArrayList<>();
                while (i < lines.length && clean(lines[i]).trim().startsWith("|")) {
                    tableLines.add(clean(lines[i]).trim());
                    i++;
                }
                i--;
                addMarkdownTable(ctx, tableLines);
                continue;
            }

            if (trimmed.matches("^([-*_])\\1{2,}$")) {
                ctx.document().add(new LineSeparator(0.5f, 100f, BORDER, Element.ALIGN_CENTER, -4f));
                ctx.document().add(spacer(8f));
                continue;
            }

            Matcher m;
            if ((m = HEADING.matcher(trimmed)).matches()) {
                int level = m.group(1).length();
                // A leading H1 just repeats the document title shown in the header band.
                if (!(firstContent && level == 1)) {
                    addMarkdownHeading(ctx, level, m.group(2));
                }
            } else if ((m = BOLD_ONLY_LINE.matcher(trimmed)).matches()) {
                addMarkdownHeading(ctx, 3, m.group(1));
            } else if ((m = BULLET.matcher(line)).matches()) {
                addListItem(ctx, "•", indentLevel(m.group(1)), m.group(2), 10f);
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                addListItem(ctx, m.group(2) + ".", indentLevel(m.group(1)), m.group(3), 14f);
            } else if (trimmed.startsWith(">")) {
                addBlockquote(ctx, trimmed.replaceFirst("^>+\\s?", ""));
            } else {
                Paragraph paragraph = inline(trimmed, 10f, TEXT);
                paragraph.setLeading(0f, 1.45f);
                paragraph.setSpacingAfter(5f);
                ctx.document().add(paragraph);
            }
        }
    }

    private void addMarkdownHeading(Ctx ctx, int level, String raw) throws DocumentException {
        String text = stripInlineMarkers(raw).replaceAll("^\\d+\\.\\s*", "").trim();
        if (text.isEmpty()) {
            return;
        }
        ensureSpace(ctx, 70f);
        Paragraph heading;
        if (level <= 2) {
            heading = new Paragraph(text, font(bold, 12.5f, INK));
            heading.setSpacingBefore(8f);
        } else {
            heading = new Paragraph(text, font(bold, 10.5f, PRIMARY_DARK));
            heading.setSpacingBefore(6f);
        }
        heading.setLeading(0f, 1.25f);
        heading.setSpacingAfter(3f);
        ctx.document().add(heading);
    }

    private void addListItem(Ctx ctx, String marker, int level, String text, float markerWidth) throws DocumentException {
        org.openpdf.text.List list = new org.openpdf.text.List(false, markerWidth);
        list.setListSymbol(new Chunk(marker, font(bold, 10f, PRIMARY)));
        list.setIndentationLeft(4f + level * 14f);
        ListItem item = new ListItem();
        item.setLeading(0f, 1.45f);
        item.setSpacingAfter(2.5f);
        item.addAll(inlineChunks(text, 10f, TEXT, regular));
        list.add(item);
        ctx.document().add(list);
    }

    private void addBlockquote(Ctx ctx, String text) throws DocumentException {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100f);
        box.setSpacingBefore(2f);
        box.setSpacingAfter(6f);
        Paragraph p = new Paragraph();
        p.setLeading(0f, 1.45f);
        p.addAll(inlineChunks(text, 9.5f, TEXT, italic));
        PdfPCell cell = new PdfPCell();
        cell.addElement(p);
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderWidthLeft(2.5f);
        cell.setBorderColorLeft(PRIMARY);
        cell.setBackgroundColor(SURFACE);
        cell.setPadding(8f);
        cell.setPaddingLeft(12f);
        box.addCell(cell);
        ctx.document().add(box);
    }

    private void addMarkdownTable(Ctx ctx, List<String> tableLines) throws DocumentException {
        List<List<String>> rows = new ArrayList<>();
        for (String raw : tableLines) {
            if (TABLE_SEPARATOR.matcher(raw).matches()) {
                continue;
            }
            String body = raw.replaceAll("^\\|", "").replaceAll("\\|$", "");
            List<String> cells = new ArrayList<>();
            for (String c : body.split("\\|", -1)) {
                cells.add(c.trim());
            }
            rows.add(cells);
        }
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.getFirst().size();
        float[] widths = new float[columns];
        for (int c = 0; c < columns; c++) {
            widths[c] = c == 0 ? 1.5f : 1f;
        }

        PdfPTable table = dataTable(widths);
        for (int c = 0; c < columns; c++) {
            String header = stripInlineMarkers(rows.getFirst().get(c));
            table.addCell(headerCell(header, c == 0 ? Element.ALIGN_LEFT : alignFor(rows, c)));
        }
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < columns; c++) {
                String value = c < row.size() ? row.get(c) : "";
                Paragraph p = inline(value, 8.5f, TEXT);
                PdfPCell cell = bodyCell(p, c == 0 ? Element.ALIGN_LEFT : alignFor(rows, c), r % 2 == 0);
                table.addCell(cell);
            }
        }
        ctx.document().add(table);
    }

    private int alignFor(List<List<String>> rows, int column) {
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String value = column < row.size() ? stripInlineMarkers(row.get(column)) : "";
            if (!value.isEmpty() && !NUMERIC_CELL.matcher(value).matches()) {
                return Element.ALIGN_LEFT;
            }
        }
        return Element.ALIGN_RIGHT;
    }

    private int indentLevel(String leading) {
        int spaces = leading.replace("\t", "    ").length();
        return Math.min(3, spaces / 2);
    }

    private Paragraph inline(String text, float size, Color color) {
        Paragraph p = new Paragraph();
        p.addAll(inlineChunks(text, size, color, regular));
        return p;
    }

    private List<Chunk> inlineChunks(String text, float size, Color color, BaseFont base) {
        List<Chunk> chunks = new ArrayList<>();
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                chunks.add(new Chunk(text.substring(last, m.start()).replace("**", ""), font(base, size, color)));
            }
            if (m.group(1) != null || m.group(2) != null) {
                String value = m.group(1) != null ? m.group(1) : m.group(2);
                chunks.add(new Chunk(value, font(bold, size, INK)));
            } else if (m.group(3) != null) {
                chunks.add(new Chunk(m.group(3), font(italic, size, color)));
            } else {
                chunks.add(new Chunk(m.group(4), font(regular, size, PRIMARY_DARK)));
            }
            last = m.end();
        }
        if (last < text.length()) {
            chunks.add(new Chunk(text.substring(last).replace("**", ""), font(base, size, color)));
        }
        return chunks;
    }

    private String stripInlineMarkers(String text) {
        return text.replace("**", "").replace("__", "").replace("`", "").trim();
    }

    // ─── Data sections ───────────────────────────────────────────────────────

    private boolean addMonthlyTrend(Ctx ctx, ReportDto.MonthlyTrendResponse trend) throws DocumentException {
        if (trend == null || trend.getItems() == null || trend.getItems().isEmpty()) {
            return false;
        }
        List<ReportDto.MonthlyTrendItem> items = trend.getItems();
        ctx.document().add(monthlyTrendChart(ctx, items));
        ensureSpace(ctx, 60f);

        PdfPTable table = dataTable(1.4f, 1.6f, 1.6f, 1.6f, 1.1f);
        table.addCell(headerCell("Mese", Element.ALIGN_LEFT));
        table.addCell(headerCell("Entrate", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Uscite", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Netto", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Risparmio", Element.ALIGN_RIGHT));

        double totalIncome = 0;
        double totalExpense = 0;
        int row = 0;
        for (ReportDto.MonthlyTrendItem item : items) {
            double income = toDouble(item.getIncome());
            double expense = toDouble(item.getExpense());
            double net = income - expense;
            totalIncome += income;
            totalExpense += expense;
            boolean zebra = row++ % 2 == 1;
            table.addCell(bodyCell(monthLabel(item.getYear(), item.getMonth()), font(regular, 9f, TEXT), Element.ALIGN_LEFT, zebra));
            table.addCell(bodyCell(money(ctx, income), font(regular, 9f, TEXT), Element.ALIGN_RIGHT, zebra));
            table.addCell(bodyCell(money(ctx, expense), font(regular, 9f, TEXT), Element.ALIGN_RIGHT, zebra));
            table.addCell(bodyCell(signedMoney(ctx, net), font(bold, 9f, net >= 0 ? INCOME : EXPENSE), Element.ALIGN_RIGHT, zebra));
            table.addCell(bodyCell(income > 0 ? percent(net / income * 100) : "–", font(regular, 9f, MUTED), Element.ALIGN_RIGHT, zebra));
        }
        double totalNet = totalIncome - totalExpense;
        table.addCell(totalCell("Totale", Element.ALIGN_LEFT, INK));
        table.addCell(totalCell(money(ctx, totalIncome), Element.ALIGN_RIGHT, INK));
        table.addCell(totalCell(money(ctx, totalExpense), Element.ALIGN_RIGHT, INK));
        table.addCell(totalCell(signedMoney(ctx, totalNet), Element.ALIGN_RIGHT, totalNet >= 0 ? INCOME : EXPENSE));
        table.addCell(totalCell(totalIncome > 0 ? percent(totalNet / totalIncome * 100) : "–", Element.ALIGN_RIGHT, MUTED));

        ctx.document().add(table);
        return true;
    }

    private boolean addCategoryBreakdown(Ctx ctx, ReportDto.CategoryBreakdownResponse breakdown) throws DocumentException {
        if (breakdown == null || breakdown.getCategories() == null || breakdown.getCategories().isEmpty()) {
            return false;
        }
        List<ReportDto.CategoryBreakdownItem> expenses = breakdown.getCategories().stream()
                .filter(c -> c.getInferredType() != TransactionType.IN)
                .sorted(Comparator.comparing((ReportDto.CategoryBreakdownItem c) -> toDouble(c.getNet())).reversed())
                .toList();
        List<ReportDto.CategoryBreakdownItem> incomes = breakdown.getCategories().stream()
                .filter(c -> c.getInferredType() == TransactionType.IN)
                .sorted(Comparator.comparing((ReportDto.CategoryBreakdownItem c) -> toDouble(c.getNet())).reversed())
                .toList();

        if (!expenses.isEmpty()) {
            ensureSpace(ctx, Math.min(70f + expenses.size() * ROW_HEIGHT, 400f));
            addSubheading(ctx, "Uscite", EXPENSE);
            addCategoryTable(ctx, expenses, EXPENSE, "Totale uscite", toDouble(breakdown.getTotalExpense()));
        }
        if (!incomes.isEmpty()) {
            ensureSpace(ctx, Math.min(70f + incomes.size() * ROW_HEIGHT, 400f));
            addSubheading(ctx, "Entrate", INCOME);
            addCategoryTable(ctx, incomes, INCOME, "Totale entrate", toDouble(breakdown.getTotalIncome()));
        }
        return true;
    }

    private void addCategoryTable(Ctx ctx, List<ReportDto.CategoryBreakdownItem> items, Color color,
                                  String totalLabel, double total) throws DocumentException {
        double max = items.stream().mapToDouble(i -> Math.abs(toDouble(i.getNet()))).max().orElse(0);

        PdfPTable table = dataTable(2.3f, 3.0f, 1.5f, 0.8f);
        table.addCell(headerCell("Categoria", Element.ALIGN_LEFT));
        table.addCell(headerCell("", Element.ALIGN_LEFT));
        table.addCell(headerCell("Importo", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Quota", Element.ALIGN_RIGHT));

        int row = 0;
        for (ReportDto.CategoryBreakdownItem item : items) {
            boolean zebra = row++ % 2 == 1;
            double value = Math.abs(toDouble(item.getNet()));
            table.addCell(bodyCell(shortLabel(item.getCategoryName(), 32), font(regular, 9f, TEXT), Element.ALIGN_LEFT, zebra));
            table.addCell(barCell(max > 0 ? value / max : 0, color, zebra));
            table.addCell(bodyCell(money(ctx, value), font(bold, 9f, INK), Element.ALIGN_RIGHT, zebra));
            table.addCell(bodyCell(percent(item.getPercentage()), font(regular, 9f, MUTED), Element.ALIGN_RIGHT, zebra));
        }
        table.addCell(totalCell(totalLabel, Element.ALIGN_LEFT, INK));
        table.addCell(totalCell("", Element.ALIGN_LEFT, INK));
        table.addCell(totalCell(money(ctx, total), Element.ALIGN_RIGHT, color));
        table.addCell(totalCell("", Element.ALIGN_RIGHT, INK));
        ctx.document().add(table);
    }

    private boolean addMonthComparison(Ctx ctx, ReportDto.MonthComparisonResponse comparison) throws DocumentException {
        if (comparison == null || comparison.getCurrentMonth() == null || comparison.getPreviousMonth() == null) {
            return false;
        }
        ReportDto.MonthComparisonItem current = comparison.getCurrentMonth();
        ReportDto.MonthComparisonItem previous = comparison.getPreviousMonth();

        PdfPTable table = dataTable(1.4f, 1.6f, 1.6f, 1.6f, 1.0f);
        table.addCell(headerCell("", Element.ALIGN_LEFT));
        table.addCell(headerCell(monthLabel(previous.getYear(), previous.getMonth()), Element.ALIGN_RIGHT));
        table.addCell(headerCell(monthLabel(current.getYear(), current.getMonth()), Element.ALIGN_RIGHT));
        table.addCell(headerCell("Variazione", Element.ALIGN_RIGHT));
        table.addCell(headerCell("%", Element.ALIGN_RIGHT));

        addComparisonRow(ctx, table, "Entrate", toDouble(previous.getIncome()), toDouble(current.getIncome()), true, false);
        addComparisonRow(ctx, table, "Uscite", toDouble(previous.getExpense()), toDouble(current.getExpense()), false, true);
        addComparisonRow(ctx, table, "Netto", toDouble(previous.getNet()), toDouble(current.getNet()), true, false);

        ctx.document().add(table);
        return true;
    }

    private void addComparisonRow(Ctx ctx, PdfPTable table, String label, double previous, double current,
                                  boolean increaseIsGood, boolean zebra) {
        double delta = current - previous;
        Color deltaColor = delta == 0 ? MUTED : (delta > 0) == increaseIsGood ? INCOME : EXPENSE;
        String pct = previous != 0 ? signedPercent(delta / Math.abs(previous) * 100) : "–";

        table.addCell(bodyCell(label, font(bold, 9f, INK), Element.ALIGN_LEFT, zebra));
        table.addCell(bodyCell(money(ctx, previous), font(regular, 9f, MUTED), Element.ALIGN_RIGHT, zebra));
        table.addCell(bodyCell(money(ctx, current), font(bold, 9f, INK), Element.ALIGN_RIGHT, zebra));
        table.addCell(bodyCell(signedMoney(ctx, delta), font(bold, 9f, deltaColor), Element.ALIGN_RIGHT, zebra));
        table.addCell(bodyCell(pct, font(regular, 9f, deltaColor), Element.ALIGN_RIGHT, zebra));
    }

    private boolean addBalanceTrend(Ctx ctx, ReportDto.BalanceTrendResponse balanceTrend) throws DocumentException {
        if (balanceTrend == null || balanceTrend.getItems() == null || balanceTrend.getItems().isEmpty()) {
            return false;
        }
        ctx.document().add(balanceChart(ctx, balanceTrend.getItems()));
        return true;
    }

    private String projectionSubtitle(ReportDto.MonthlyProjection projection) {
        if (projection == null || projection.getDaysInMonth() == 0) {
            return null;
        }
        return "Giorno " + projection.getDaysElapsed() + " di " + projection.getDaysInMonth()
                + " di " + monthLabel(projection.getYear(), projection.getMonth()).toLowerCase(LOCALE)
                + ": stima basata sul ritmo di spesa attuale";
    }

    private boolean addProjection(Ctx ctx, ReportDto.MonthlyProjection projection) throws DocumentException {
        if (projection == null) {
            return false;
        }
        double currIncome = toDouble(projection.getCurrentMonthIncome());
        double projIncome = toDouble(projection.getProjectedMonthlyIncome());
        double currExpense = toDouble(projection.getCurrentMonthExpense());
        double projExpense = toDouble(projection.getProjectedMonthlyExpense());

        PdfPTable table = dataTable(1.2f, 1.5f, 2.6f, 1.6f);
        table.addCell(headerCell("", Element.ALIGN_LEFT));
        table.addCell(headerCell("Ad oggi", Element.ALIGN_RIGHT));
        table.addCell(headerCell("", Element.ALIGN_LEFT));
        table.addCell(headerCell("Stima fine mese", Element.ALIGN_RIGHT));

        table.addCell(bodyCell("Entrate", font(bold, 9f, INK), Element.ALIGN_LEFT, false));
        table.addCell(bodyCell(money(ctx, currIncome), font(regular, 9f, TEXT), Element.ALIGN_RIGHT, false));
        table.addCell(barCell(projIncome > 0 ? currIncome / projIncome : 0, INCOME, false));
        table.addCell(bodyCell(money(ctx, projIncome), font(bold, 9f, INCOME), Element.ALIGN_RIGHT, false));

        table.addCell(bodyCell("Uscite", font(bold, 9f, INK), Element.ALIGN_LEFT, true));
        table.addCell(bodyCell(money(ctx, currExpense), font(regular, 9f, TEXT), Element.ALIGN_RIGHT, true));
        table.addCell(barCell(projExpense > 0 ? currExpense / projExpense : 0, EXPENSE, true));
        table.addCell(bodyCell(money(ctx, projExpense), font(bold, 9f, EXPENSE), Element.ALIGN_RIGHT, true));

        double projNet = projIncome - projExpense;
        table.addCell(totalCell("Netto stimato", Element.ALIGN_LEFT, INK));
        table.addCell(totalCell(signedMoney(ctx, currIncome - currExpense), Element.ALIGN_RIGHT, MUTED));
        table.addCell(totalCell("", Element.ALIGN_LEFT, INK));
        table.addCell(totalCell(signedMoney(ctx, projNet), Element.ALIGN_RIGHT, projNet >= 0 ? INCOME : EXPENSE));

        ctx.document().add(table);
        return true;
    }

    private boolean addBudgetSummary(Ctx ctx, List<BudgetDto.MonthlySummaryResponse> budgetSummary) throws DocumentException {
        if (budgetSummary == null || budgetSummary.isEmpty()) {
            return false;
        }
        List<BudgetDto.MonthlySummaryResponse> sorted = budgetSummary.stream()
                .sorted(Comparator.comparingDouble(BudgetDto.MonthlySummaryResponse::getPercentageUsed).reversed())
                .toList();

        PdfPTable table = dataTable(2.0f, 2.4f, 0.8f, 1.9f, 1.3f);
        table.addCell(headerCell("Categoria", Element.ALIGN_LEFT));
        table.addCell(headerCell("Utilizzo", Element.ALIGN_LEFT));
        table.addCell(headerCell("%", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Speso / Limite", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Residuo", Element.ALIGN_RIGHT));

        int row = 0;
        for (BudgetDto.MonthlySummaryResponse item : sorted) {
            boolean zebra = row++ % 2 == 1;
            double pct = item.getPercentageUsed();
            Color color = pct >= 100 ? EXPENSE : pct >= 75 ? WARNING : INCOME;
            double remaining = toDouble(item.getRemaining());

            Paragraph name = new Paragraph();
            name.setLeading(0f, 1.35f);
            name.add(new Chunk(shortLabel(item.getCategoryName(), 30), font(regular, 9f, TEXT)));
            if (item.getPeriodStart() != null && item.getPeriodEnd() != null) {
                name.add(Chunk.NEWLINE);
                name.add(new Chunk(item.getPeriodStart().format(SHORT_DATE_FORMAT) + " – " + item.getPeriodEnd().format(SHORT_DATE_FORMAT),
                        font(regular, 7.5f, MUTED)));
            }
            table.addCell(bodyCell(name, Element.ALIGN_LEFT, zebra));
            table.addCell(barCell(Math.max(0, Math.min(1, pct / 100)), color, zebra));
            table.addCell(bodyCell(percent(pct), font(bold, 9f, color), Element.ALIGN_RIGHT, zebra));
            table.addCell(bodyCell(money(ctx, toDouble(item.getSpent())) + " / " + money(ctx, toDouble(item.getLimit())),
                    font(regular, 9f, TEXT), Element.ALIGN_RIGHT, zebra));
            table.addCell(bodyCell(signedMoney(ctx, remaining), font(bold, 9f, remaining < 0 ? EXPENSE : INK), Element.ALIGN_RIGHT, zebra));
        }
        ctx.document().add(table);
        return true;
    }

    // ─── Charts ──────────────────────────────────────────────────────────────

    private Image monthlyTrendChart(Ctx ctx, List<ReportDto.MonthlyTrendItem> items) throws DocumentException {
        float width = ctx.contentWidth();
        PdfTemplate tpl = ctx.writer().getDirectContent().createTemplate(width, CHART_HEIGHT);

        float plotX = 52f;
        float plotY = 20f;
        float plotW = width - plotX - 4f;
        float plotH = CHART_HEIGHT - plotY - 26f;

        double max = 0;
        for (ReportDto.MonthlyTrendItem item : items) {
            max = Math.max(max, Math.max(toDouble(item.getIncome()), toDouble(item.getExpense())));
        }
        Axis axis = Axis.of(0, max, true);
        drawGrid(tpl, axis, plotX, plotY, plotW, plotH);

        int n = items.size();
        float groupW = plotW / n;
        float barW = Math.min(16f, groupW * 0.3f);
        int labelStep = Math.max(1, (int) Math.ceil(n / 12.0));
        for (int i = 0; i < n; i++) {
            ReportDto.MonthlyTrendItem item = items.get(i);
            float cx = plotX + groupW * (i + 0.5f);
            drawBar(tpl, cx - barW - 1f, plotY, barW, (float) (toDouble(item.getIncome()) / axis.max() * plotH), INCOME);
            drawBar(tpl, cx + 1f, plotY, barW, (float) (toDouble(item.getExpense()) / axis.max() * plotH), EXPENSE);
            if (i % labelStep == 0) {
                text(tpl, regular, 7.5f, MUTED, Element.ALIGN_CENTER, shortMonthLabel(item.getYear(), item.getMonth()), cx, plotY - 12f);
            }
        }

        drawLegend(tpl, width, CHART_HEIGHT - 10f, new String[] {"Entrate", "Uscite"}, new Color[] {INCOME, EXPENSE});
        return chartImage(tpl);
    }

    private Image balanceChart(Ctx ctx, List<ReportDto.BalanceTrendItem> items) throws DocumentException {
        float width = ctx.contentWidth();
        PdfTemplate tpl = ctx.writer().getDirectContent().createTemplate(width, CHART_HEIGHT);

        float plotX = 52f;
        float plotY = 20f;
        float plotW = width - plotX - 14f;
        float plotH = CHART_HEIGHT - plotY - 10f;

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (ReportDto.BalanceTrendItem item : items) {
            double v = toDouble(item.getClosingBalance());
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        // Balance is a level, not a quantity: a zero baseline would flatten month-to-month changes.
        Axis axis = Axis.of(min, max, false);
        drawGrid(tpl, axis, plotX, plotY, plotW, plotH);

        int n = items.size();
        float step = n > 1 ? plotW / (n - 1) : 0f;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n > 1 ? plotX + step * i : plotX + plotW / 2;
            ys[i] = plotY + (float) ((toDouble(items.get(i).getClosingBalance()) - axis.min()) / (axis.max() - axis.min()) * plotH);
        }

        // Area under the line.
        if (n > 1) {
            tpl.saveState();
            PdfGState area = new PdfGState();
            area.setFillOpacity(0.12f);
            tpl.setGState(area);
            tpl.setColorFill(BALANCE);
            tpl.moveTo(xs[0], plotY);
            for (int i = 0; i < n; i++) {
                tpl.lineTo(xs[i], ys[i]);
            }
            tpl.lineTo(xs[n - 1], plotY);
            tpl.closePath();
            tpl.fill();
            tpl.restoreState();

            tpl.saveState();
            tpl.setColorStroke(BALANCE);
            tpl.setLineWidth(2f);
            tpl.setLineJoin(PdfContentByte.LINE_JOIN_ROUND);
            tpl.moveTo(xs[0], ys[0]);
            for (int i = 1; i < n; i++) {
                tpl.lineTo(xs[i], ys[i]);
            }
            tpl.stroke();
            tpl.restoreState();
        }

        int labelStep = Math.max(1, (int) Math.ceil(n / 12.0));
        for (int i = 0; i < n; i++) {
            tpl.saveState();
            tpl.setColorFill(Color.WHITE);
            tpl.setColorStroke(BALANCE);
            tpl.setLineWidth(1.5f);
            tpl.circle(xs[i], ys[i], 2.8f);
            tpl.fillStroke();
            tpl.restoreState();
            if (i % labelStep == 0 || i == n - 1) {
                ReportDto.BalanceTrendItem item = items.get(i);
                text(tpl, regular, 7.5f, MUTED, Element.ALIGN_CENTER, shortMonthLabel(item.getYear(), item.getMonth()), xs[i], plotY - 12f);
            }
        }

        // Highlight the final balance.
        String last = money(ctx, toDouble(items.get(n - 1).getClosingBalance()));
        float labelW = bold.getWidthPoint(last, 8f) + 10f;
        float lx = Math.min(xs[n - 1] - labelW / 2, plotX + plotW - labelW + 10f);
        float ly = Math.min(ys[n - 1] + 8f, plotY + plotH - 14f);
        tpl.saveState();
        tpl.setColorFill(BALANCE);
        tpl.roundRectangle(lx, ly, labelW, 14f, 4f);
        tpl.fill();
        tpl.restoreState();
        text(tpl, bold, 8f, Color.WHITE, Element.ALIGN_CENTER, last, lx + labelW / 2, ly + 4f);

        return chartImage(tpl);
    }

    private record Axis(double min, double max, double step) {
        static Axis of(double min, double max, boolean includeZero) {
            if (includeZero) {
                min = Math.min(min, 0);
                max = Math.max(max, 0);
            } else {
                double pad = Math.max((max - min) * 0.15, Math.abs(max) * 0.02);
                min -= pad;
                max += pad;
            }
            if (max - min < 1e-9) {
                max = min + 1;
            }
            double step = niceStep((max - min) / 4);
            return new Axis(Math.floor(min / step) * step, Math.ceil(max / step) * step, step);
        }

        private static double niceStep(double raw) {
            double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
            double f = raw / magnitude;
            double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
            return nice * magnitude;
        }
    }

    private void drawGrid(PdfTemplate tpl, Axis axis, float plotX, float plotY, float plotW, float plotH) {
        int ticks = (int) Math.round((axis.max() - axis.min()) / axis.step());
        for (int t = 0; t <= ticks; t++) {
            double value = axis.min() + axis.step() * t;
            float y = plotY + (float) ((value - axis.min()) / (axis.max() - axis.min()) * plotH);
            boolean zero = Math.abs(value) < axis.step() / 1000;
            tpl.saveState();
            tpl.setColorStroke(zero ? new Color(203, 213, 225) : BORDER);
            tpl.setLineWidth(zero ? 0.8f : 0.5f);
            if (!zero) {
                tpl.setLineDash(2f, 2f);
            }
            tpl.moveTo(plotX, y);
            tpl.lineTo(plotX + plotW, y);
            tpl.stroke();
            tpl.restoreState();
            text(tpl, regular, 7f, MUTED, Element.ALIGN_RIGHT, compact(value), plotX - 6f, y - 2.5f);
        }
    }

    private void drawBar(PdfTemplate tpl, float x, float baseY, float w, float h, Color color) {
        if (h <= 0.1f) {
            return;
        }
        float r = Math.min(2.5f, Math.min(w / 2, h / 2));
        tpl.saveState();
        tpl.setColorFill(color);
        tpl.roundRectangle(x, baseY, w, h, r);
        tpl.fill();
        // Square off the bottom corners so bars sit flush on the axis.
        tpl.rectangle(x, baseY, w, Math.min(h, r));
        tpl.fill();
        tpl.restoreState();
    }

    private void drawLegend(PdfTemplate tpl, float right, float y, String[] labels, Color[] colors) {
        float x = right;
        for (int i = labels.length - 1; i >= 0; i--) {
            float textW = regular.getWidthPoint(labels[i], 8f);
            x -= textW;
            text(tpl, regular, 8f, TEXT, Element.ALIGN_LEFT, labels[i], x, y);
            x -= 12f;
            tpl.saveState();
            tpl.setColorFill(colors[i]);
            tpl.roundRectangle(x, y - 0.5f, 8f, 8f, 2f);
            tpl.fill();
            tpl.restoreState();
            x -= 14f;
        }
    }

    private void text(PdfContentByte cb, BaseFont font, float size, Color color, int align, String value, float x, float y) {
        cb.saveState();
        cb.beginText();
        cb.setFontAndSize(font, size);
        cb.setColorFill(color);
        cb.showTextAligned(align, value, x, y, 0f);
        cb.endText();
        cb.restoreState();
    }

    private Image chartImage(PdfTemplate tpl) throws DocumentException {
        Image image = Image.getInstance(tpl);
        image.setAlignment(Image.MIDDLE);
        image.setSpacingBefore(4f);
        image.setSpacingAfter(8f);
        return image;
    }

    // ─── Table helpers ───────────────────────────────────────────────────────

    private PdfPTable dataTable(float... widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100f);
        table.setHeaderRows(1);
        table.setKeepTogether(true);
        table.setSpacingBefore(4f);
        table.setSpacingAfter(6f);
        return table;
    }

    private PdfPCell headerCell(String text, int align) {
        Chunk chunk = new Chunk(text.toUpperCase(LOCALE), font(bold, 7.5f, MUTED));
        chunk.setCharacterSpacing(0.5f);
        PdfPCell cell = new PdfPCell(new Phrase(chunk));
        cell.setHorizontalAlignment(align);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(BORDER);
        cell.setBorderWidthBottom(1f);
        cell.setPaddingTop(4f);
        cell.setPaddingBottom(6f);
        cell.setPaddingLeft(6f);
        cell.setPaddingRight(6f);
        return cell;
    }

    private PdfPCell bodyCell(String text, Font font, int align, boolean zebra) {
        return bodyCell(new Phrase(text != null ? text : "", font), align, zebra);
    }

    private PdfPCell bodyCell(Phrase content, int align, boolean zebra) {
        PdfPCell cell = new PdfPCell();
        if (content instanceof Paragraph paragraph) {
            paragraph.setAlignment(align);
            cell.addElement(paragraph);
            cell.setPaddingTop(2f);
        } else {
            cell.setPhrase(content);
            cell.setPaddingTop(6f);
        }
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setMinimumHeight(22f);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(new Color(241, 245, 249));
        cell.setBorderWidthBottom(0.5f);
        cell.setPaddingBottom(6f);
        cell.setPaddingLeft(6f);
        cell.setPaddingRight(6f);
        if (zebra) {
            cell.setBackgroundColor(SURFACE);
        }
        return cell;
    }

    private PdfPCell totalCell(String text, int align, Color color) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(bold, 9f, color)));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColorTop(new Color(203, 213, 225));
        cell.setBorderWidthTop(1f);
        cell.setPadding(6f);
        cell.setPaddingTop(7f);
        return cell;
    }

    private PdfPCell barCell(double fraction, Color color, boolean zebra) {
        PdfPCell cell = bodyCell("", font(regular, 9f, TEXT), Element.ALIGN_LEFT, zebra);
        cell.setCellEvent(new ProgressBar(Math.max(0, Math.min(1, fraction)), color));
        return cell;
    }

    // ─── Cell & page events ──────────────────────────────────────────────────

    /** Rounded track with a proportional fill, vertically centred in the cell. */
    private record ProgressBar(double fraction, Color color) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
            float x = position.getLeft() + 6f;
            float w = position.getWidth() - 12f;
            float y = (position.getTop() + position.getBottom()) / 2 - BAR_HEIGHT / 2;
            cb.saveState();
            cb.setColorFill(TRACK);
            cb.roundRectangle(x, y, w, BAR_HEIGHT, BAR_HEIGHT / 2);
            cb.fill();
            float fillW = (float) (w * fraction);
            if (fillW > 0.5f) {
                cb.setColorFill(color);
                cb.roundRectangle(x, y, Math.max(fillW, BAR_HEIGHT), BAR_HEIGHT, BAR_HEIGHT / 2);
                cb.fill();
            }
            cb.restoreState();
        }
    }

    /** Rounded background, optionally inset horizontally to leave gaps between adjacent cells. */
    private record RoundedFill(Color color, float radius, float inset) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            cb.saveState();
            cb.setColorFill(color);
            cb.roundRectangle(position.getLeft() + inset, position.getBottom(),
                    position.getWidth() - 2 * inset, position.getHeight(), radius);
            cb.fill();
            cb.restoreState();
        }
    }

    /** KPI card: light rounded surface, hairline border and a short accent bar on top. */
    private record CardBackground(Color accent) implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            float x = position.getLeft() + 3f;
            float w = position.getWidth() - 6f;
            float y = position.getBottom();
            float h = position.getHeight();
            cb.saveState();
            cb.setColorFill(SURFACE);
            cb.setColorStroke(BORDER);
            cb.setLineWidth(0.75f);
            cb.roundRectangle(x, y, w, h, 7f);
            cb.fillStroke();
            cb.setColorFill(accent);
            cb.roundRectangle(x + 11f, y + h - 3f, 22f, 3f, 1.5f);
            cb.fill();
            cb.restoreState();
        }
    }

    /** Running header (from page 2) and "Pagina X di Y" footer. */
    private final class PageDecorator extends PdfPageEventHelper {
        private final String period;
        private PdfTemplate totalPages;

        private PageDecorator(LocalDate startDate, LocalDate endDate) {
            this.period = startDate.format(DATE_FORMAT) + " – " + endDate.format(DATE_FORMAT);
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPages = writer.getDirectContent().createTemplate(24f, 12f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float left = document.left();
            float right = document.right();

            if (writer.getPageNumber() > 1) {
                float y = document.top() + 18f;
                text(cb, bold, 8f, PRIMARY, Element.ALIGN_LEFT, "nexaBudget", left, y);
                text(cb, regular, 8f, MUTED, Element.ALIGN_RIGHT, "Report finanziario  ·  " + period, right, y);
                hairline(cb, left, right, y - 6f);
            }

            float y = document.bottom() - 30f;
            hairline(cb, left, right, y + 12f);
            text(cb, regular, 8f, MUTED, Element.ALIGN_LEFT, "Generato da nexaBudget  ·  " + period, left, y);

            String label = "Pagina " + writer.getPageNumber() + " di ";
            float labelW = regular.getWidthPoint(label, 8f);
            float x = right - 10f - labelW;
            text(cb, regular, 8f, MUTED, Element.ALIGN_LEFT, label, x, y);
            cb.addTemplate(totalPages, x + labelW, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalPages.beginText();
            totalPages.setFontAndSize(regular, 8f);
            totalPages.setColorFill(MUTED);
            totalPages.showTextAligned(Element.ALIGN_LEFT, String.valueOf(writer.getPageNumber() - 1), 0f, 0f, 0f);
            totalPages.endText();
        }

        private void hairline(PdfContentByte cb, float left, float right, float y) {
            cb.saveState();
            cb.setColorStroke(BORDER);
            cb.setLineWidth(0.5f);
            cb.moveTo(left, y);
            cb.lineTo(right, y);
            cb.stroke();
            cb.restoreState();
        }
    }

    // ─── Formatting ──────────────────────────────────────────────────────────

    private Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    private Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ", font(regular, 1f, Color.WHITE));
        p.setSpacingAfter(height);
        return p;
    }

    private String currencySymbol(String code) {
        try {
            String symbol = Currency.getInstance(code).getSymbol(LOCALE);
            return clean(symbol).equals(symbol) ? symbol : code;
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    private DecimalFormat amountFormat(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(LOCALE));
    }

    private String money(Ctx ctx, double value) {
        return amountFormat("#,##0.00").format(value) + " " + ctx.currencySymbol();
    }

    private String signedMoney(Ctx ctx, double value) {
        String formatted = money(ctx, Math.abs(value));
        if (value > 0.004) {
            return "+" + formatted;
        }
        if (value < -0.004) {
            return "-" + formatted;
        }
        return formatted;
    }

    private String percent(double value) {
        return amountFormat("0.0").format(value) + "%";
    }

    private String signedPercent(double value) {
        return (value > 0 ? "+" : "") + percent(value);
    }

    private String compact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000) {
            return amountFormat("0.#").format(value / 1_000_000) + "M";
        }
        if (abs >= 1_000) {
            return amountFormat("0.#").format(value / 1_000) + "k";
        }
        return amountFormat("0").format(value);
    }

    private String shortMonthLabel(int year, int month) {
        return Month.of(month).getDisplayName(TextStyle.SHORT, LOCALE) + " " + String.format("%02d", year % 100);
    }

    private String monthLabel(int year, int month) {
        String name = Month.of(month).getDisplayName(TextStyle.FULL, LOCALE);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + year;
    }

    private static boolean hasItems(java.util.Collection<?> items) {
        return items != null && !items.isEmpty();
    }

    private double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    private String shortLabel(String label, int maxLen) {
        if (label == null) {
            return "n/a";
        }
        String normalized = clean(label).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    /**
     * The standard Helvetica font only covers WinAnsi (CP1252): characters outside it (emoji,
     * arrows, math symbols) would be silently dropped or rendered as garbage, so common ones are
     * mapped to ASCII equivalents and the rest removed.
     */
    private String clean(String text) {
        if (text == null) {
            return "";
        }
        String mapped = text
                .replace("→", "->").replace("←", "<-").replace("⇒", "=>")
                .replace("≥", ">=").replace("≤", "<=").replace("≈", "~").replace("≠", "!=")
                .replace("✓", "").replace("✔", "").replace("✅", "");
        CharsetEncoder winAnsi = Charset.forName("windows-1252").newEncoder();
        StringBuilder sb = new StringBuilder(mapped.length());
        mapped.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            if (cp < 0x80 || winAnsi.canEncode(ch)) {
                sb.append(ch);
            }
        });
        if (sb.length() == mapped.length()) {
            return mapped;
        }
        return sb.toString()
                .replaceAll("(?<=\\S) {2,}(?=\\S)", " ")
                .replaceAll("(?<=\\S) +(?=[.,;:!?])", "")
                .stripTrailing();
    }
}
