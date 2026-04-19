package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class ImportDto {

    /**
     * CSV column mapping — all indexes are 0-based.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CsvColumnMapping {
        @NotNull
        private Integer dateColumn;
        @NotNull
        private Integer amountColumn;
        @NotNull
        private Integer descriptionColumn;
        /** Optional: column containing IN/OUT or CREDIT/DEBIT label. If absent, sign of amount determines type. */
        private Integer typeColumn;
        /** Date pattern, e.g. "dd/MM/yyyy" or "yyyy-MM-dd". Default: "yyyy-MM-dd". */
        @Builder.Default
        private String dateFormat = "yyyy-MM-dd";
        /** CSV delimiter. Default: ",". */
        @Builder.Default
        private String delimiter = ",";
        /** Whether the first row is a header. Default: true. */
        @Builder.Default
        private boolean hasHeader = true;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportedTransactionPreview {
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;
        private BigDecimal amount;
        private TransactionType type;
        private String description;
        /** True if a transaction with the same hash already exists for this account. */
        private boolean duplicate;
        /** Opaque hash — pass it back in ImportConfirmRequest.selectedHashes to include this row. */
        private String importHash;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportPreviewResponse {
        private int total;
        private int duplicates;
        private int toImport;
        private List<ImportedTransactionPreview> transactions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportConfirmRequest {
        /**
         * Hashes of rows to import. If null/empty all non-duplicate rows are imported.
         */
        private List<String> selectedHashes;
        /** Optional: category ID to assign when AI fails. */
        private java.util.UUID defaultCategoryId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportResult {
        private int imported;
        private int skipped;
        private int errors;
    }
}
