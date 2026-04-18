package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.ImportDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImportService {

    private static final Logger logger = LoggerFactory.getLogger(ImportService.class);

    private final TransactionRepository transactionRepository;
    private final AiCategorizationService aiCategorizationService;

    public ImportService(TransactionRepository transactionRepository,
                         AiCategorizationService aiCategorizationService) {
        this.transactionRepository = transactionRepository;
        this.aiCategorizationService = aiCategorizationService;
    }

    // ─── Preview ────────────────────────────────────────────────────────────────

    public ImportDto.ImportPreviewResponse previewCsv(MultipartFile file,
                                                       ImportDto.CsvColumnMapping mapping,
                                                       Account account) throws IOException {
        List<ParsedRow> rows = parseCsv(file, mapping);
        return buildPreview(rows, account);
    }

    public ImportDto.ImportPreviewResponse previewOfx(MultipartFile file, Account account) throws IOException {
        List<ParsedRow> rows = parseOfx(file);
        return buildPreview(rows, account);
    }

    // ─── Import ─────────────────────────────────────────────────────────────────

    @Transactional
    public ImportDto.ImportResult importCsv(MultipartFile file,
                                             ImportDto.CsvColumnMapping mapping,
                                             Account account,
                                             User user,
                                             ImportDto.ImportConfirmRequest confirm,
                                             Category defaultCategory) throws IOException {
        List<ParsedRow> rows = parseCsv(file, mapping);
        return doImport(rows, account, user, confirm, defaultCategory);
    }

    @Transactional
    public ImportDto.ImportResult importOfx(MultipartFile file,
                                             Account account,
                                             User user,
                                             ImportDto.ImportConfirmRequest confirm,
                                             Category defaultCategory) throws IOException {
        List<ParsedRow> rows = parseOfx(file);
        return doImport(rows, account, user, confirm, defaultCategory);
    }

    // ─── CSV Parsing ────────────────────────────────────────────────────────────

    private List<ParsedRow> parseCsv(MultipartFile file, ImportDto.CsvColumnMapping mapping) throws IOException {
        char delimiter = mapping.getDelimiter() != null && !mapping.getDelimiter().isEmpty()
                ? mapping.getDelimiter().charAt(0) : ',';

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setSkipHeaderRecord(mapping.isHasHeader())
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        List<ParsedRow> result = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(
                mapping.getDateFormat() != null ? mapping.getDateFormat() : "yyyy-MM-dd");

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {

            for (CSVRecord record : parser) {
                try {
                    String dateStr = record.get(mapping.getDateColumn()).trim();
                    String amountStr = record.get(mapping.getAmountColumn())
                            .replace(",", ".").replaceAll("[^\\d.\\-]", "").trim();
                    String description = record.get(mapping.getDescriptionColumn()).trim();

                    if (dateStr.isEmpty() || amountStr.isEmpty() || description.isEmpty()) {
                        continue;
                    }

                    LocalDate date = LocalDate.parse(dateStr, dtf);
                    BigDecimal rawAmount = new BigDecimal(amountStr);
                    TransactionType type;
                    BigDecimal amount;

                    if (mapping.getTypeColumn() != null) {
                        String typeStr = record.get(mapping.getTypeColumn()).trim().toUpperCase();
                        type = typeStr.contains("IN") || typeStr.contains("CREDIT") || typeStr.contains("ENTRATA")
                                ? TransactionType.IN : TransactionType.OUT;
                        amount = rawAmount.abs();
                    } else {
                        type = rawAmount.signum() >= 0 ? TransactionType.IN : TransactionType.OUT;
                        amount = rawAmount.abs();
                    }

                    result.add(new ParsedRow(date, amount, type, description, null));
                } catch (Exception e) {
                    logger.warn("Riga CSV ignorata (riga {}): {}", record.getRecordNumber(), e.getMessage());
                }
            }
        }
        return result;
    }

    // ─── OFX Parsing ────────────────────────────────────────────────────────────

    private List<ParsedRow> parseOfx(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        // Detect OFX 2.x (XML) vs 1.x (SGML)
        if (content.trim().startsWith("<?xml") || content.contains("<OFX>") && content.contains("</OFX>")) {
            return parseOfxXml(content);
        }
        return parseOfxSgml(content);
    }

    /**
     * Parses OFX 1.x SGML format (no closing tags, line-based key:value header).
     */
    private List<ParsedRow> parseOfxSgml(String content) {
        List<ParsedRow> result = new ArrayList<>();
        // Split on <STMTTRN> blocks
        String[] blocks = content.split("(?i)<STMTTRN>");
        for (int i = 1; i < blocks.length; i++) {
            try {
                String block = blocks[i];
                String dateStr = extractSgmlTag(block, "DTPOSTED");
                String amountStr = extractSgmlTag(block, "TRNAMT");
                String name = extractSgmlTag(block, "NAME");
                String memo = extractSgmlTag(block, "MEMO");
                String fitId = extractSgmlTag(block, "FITID");

                if (dateStr == null || amountStr == null) continue;

                // OFX date format: YYYYMMDD[HHmmss[.xxx][TZD]]
                LocalDate date = LocalDate.parse(dateStr.substring(0, 8),
                        DateTimeFormatter.ofPattern("yyyyMMdd"));
                BigDecimal rawAmount = new BigDecimal(amountStr.replace(",", ".").trim());
                TransactionType type = rawAmount.signum() >= 0 ? TransactionType.IN : TransactionType.OUT;
                String description = name != null ? name : (memo != null ? memo : "");

                result.add(new ParsedRow(date, rawAmount.abs(), type, description, fitId));
            } catch (Exception e) {
                logger.warn("Blocco OFX SGML ignorato: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * Parses OFX 2.x XML format.
     */
    private List<ParsedRow> parseOfxXml(String content) {
        List<ParsedRow> result = new ArrayList<>();
        Pattern stmtTrnPattern = Pattern.compile(
                "<STMTTRN>(.*?)</STMTTRN>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = stmtTrnPattern.matcher(content);
        while (matcher.find()) {
            try {
                String block = matcher.group(1);
                String dateStr = extractXmlTag(block, "DTPOSTED");
                String amountStr = extractXmlTag(block, "TRNAMT");
                String name = extractXmlTag(block, "NAME");
                String memo = extractXmlTag(block, "MEMO");
                String fitId = extractXmlTag(block, "FITID");

                if (dateStr == null || amountStr == null) continue;

                LocalDate date = LocalDate.parse(dateStr.substring(0, 8),
                        DateTimeFormatter.ofPattern("yyyyMMdd"));
                BigDecimal rawAmount = new BigDecimal(amountStr.replace(",", ".").trim());
                TransactionType type = rawAmount.signum() >= 0 ? TransactionType.IN : TransactionType.OUT;
                String description = name != null ? name : (memo != null ? memo : "");

                result.add(new ParsedRow(date, rawAmount.abs(), type, description, fitId));
            } catch (Exception e) {
                logger.warn("Blocco OFX XML ignorato: {}", e.getMessage());
            }
        }
        return result;
    }

    private String extractSgmlTag(String block, String tag) {
        Pattern p = Pattern.compile("(?i)<" + tag + ">([^<\\r\\n]+)");
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractXmlTag(String block, String tag) {
        Pattern p = Pattern.compile("(?i)<" + tag + ">([^<]+)</" + tag + ">");
        Matcher m = p.matcher(block);
        if (m.find()) return m.group(1).trim();
        // Fallback: SGML-style (no closing tag) inside XML file
        return extractSgmlTag(block, tag);
    }

    // ─── Common logic ───────────────────────────────────────────────────────────

    private ImportDto.ImportPreviewResponse buildPreview(List<ParsedRow> rows, Account account) {
        List<ImportDto.ImportedTransactionPreview> previews = new ArrayList<>();
        int duplicateCount = 0;
        for (ParsedRow row : rows) {
            String hash = computeHash(account.getId(), row.date(), row.amount(), row.description());
            boolean isDuplicate = isDuplicate(hash, row.fitId());
            if (isDuplicate) duplicateCount++;
            previews.add(ImportDto.ImportedTransactionPreview.builder()
                    .date(row.date())
                    .amount(row.amount())
                    .type(row.type())
                    .description(row.description())
                    .duplicate(isDuplicate)
                    .importHash(hash)
                    .build());
        }
        return ImportDto.ImportPreviewResponse.builder()
                .total(rows.size())
                .duplicates(duplicateCount)
                .toImport(rows.size() - duplicateCount)
                .transactions(previews)
                .build();
    }

    private ImportDto.ImportResult doImport(List<ParsedRow> rows,
                                             Account account,
                                             User user,
                                             ImportDto.ImportConfirmRequest confirm,
                                             Category defaultCategory) {
        Set<String> selectedHashes = confirm != null && confirm.getSelectedHashes() != null
                ? new HashSet<>(confirm.getSelectedHashes()) : null;

        int imported = 0, skipped = 0, errors = 0;

        for (ParsedRow row : rows) {
            String hash = computeHash(account.getId(), row.date(), row.amount(), row.description());

            // Skip if not in selected set (when caller explicitly chose rows)
            if (selectedHashes != null && !selectedHashes.contains(hash)) {
                skipped++;
                continue;
            }

            // Deduplication: skip existing
            if (isDuplicate(hash, row.fitId())) {
                skipped++;
                continue;
            }

            try {
                Category category = defaultCategory;
                Optional<Category> aiCategory = aiCategorizationService
                        .categorizeTransaction(row.description(), user, row.type());
                if (aiCategory.isPresent()) {
                    category = aiCategory.get();
                }

                Transaction t = Transaction.builder()
                        .user(user)
                        .account(account)
                        .amount(row.amount())
                        .type(row.type())
                        .description(row.description())
                        .date(row.date())
                        .category(category)
                        .importHash(hash)
                        .externalId(row.fitId())
                        .build();
                transactionRepository.save(t);
                imported++;
            } catch (Exception e) {
                logger.error("Errore importazione riga {}: {}", row.description(), e.getMessage());
                errors++;
            }
        }

        logger.info("Importazione completata: imported={}, skipped={}, errors={}", imported, skipped, errors);
        return ImportDto.ImportResult.builder()
                .imported(imported)
                .skipped(skipped)
                .errors(errors)
                .build();
    }

    private boolean isDuplicate(String importHash, String fitId) {
        if (fitId != null && !fitId.isEmpty() && transactionRepository.findByExternalId(fitId).isPresent()) {
            return true;
        }
        return transactionRepository.existsByImportHash(importHash);
    }

    private static String computeHash(UUID accountId, LocalDate date, BigDecimal amount, String description) {
        String raw = accountId + "|" + date + "|" + amount.toPlainString() + "|"
                + (description != null ? description.toLowerCase().trim() : "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    private record ParsedRow(LocalDate date, BigDecimal amount, TransactionType type,
                              String description, String fitId) {}
}
