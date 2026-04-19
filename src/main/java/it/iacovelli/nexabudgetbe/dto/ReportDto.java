package it.iacovelli.nexabudgetbe.dto;

import it.iacovelli.nexabudgetbe.model.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ReportDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyTrendItem {
        private int year;
        private int month;
        private BigDecimal income;
        private BigDecimal expense;
        private BigDecimal net;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryBreakdownItem {
        private UUID categoryId;
        private String categoryName;
        private BigDecimal total;
        private double percentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryBreakdownResponse {
        private TransactionType type;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal grandTotal;
        private List<CategoryBreakdownItem> categories;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthComparisonItem {
        private int year;
        private int month;
        private BigDecimal income;
        private BigDecimal expense;
        private BigDecimal net;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthComparisonResponse {
        private MonthComparisonItem currentMonth;
        private MonthComparisonItem previousMonth;
        private BigDecimal incomeChange;
        private BigDecimal expenseChange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyProjection {
        private int year;
        private int month;
        private BigDecimal currentMonthExpense;
        private BigDecimal currentMonthIncome;
        private BigDecimal projectedMonthlyExpense;
        private BigDecimal projectedMonthlyIncome;
        private LocalDate projectionDate;
        private int daysElapsed;
        private int daysInMonth;
    }
}
