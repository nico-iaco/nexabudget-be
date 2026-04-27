package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReportService {

    private final TransactionRepository transactionRepository;

    public ReportService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public List<ReportDto.MonthlyTrendItem> getMonthlyTrend(User user, int months) {
        LocalDate from = LocalDate.now().minusMonths(months).withDayOfMonth(1);
        List<Object[]> rows = transactionRepository.findMonthlyTotals(user, from);

        // Aggregate rows: each row is [year, month, type, total]
        Map<String, ReportDto.MonthlyTrendItem> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            TransactionType type = TransactionType.valueOf(row[2].toString());
            BigDecimal total = (BigDecimal) row[3];

            String key = year + "-" + month;
            ReportDto.MonthlyTrendItem item = map.computeIfAbsent(key, k ->
                    ReportDto.MonthlyTrendItem.builder()
                            .year(year).month(month)
                            .income(BigDecimal.ZERO).expense(BigDecimal.ZERO).net(BigDecimal.ZERO)
                            .build());

            if (type == TransactionType.IN) {
                item.setIncome(total);
            } else {
                item.setExpense(total);
            }
            item.setNet(item.getIncome().subtract(item.getExpense()));
        }

        return new ArrayList<>(map.values());
    }

    @Transactional(readOnly = true)
    public ReportDto.CategoryBreakdownResponse getCategoryBreakdown(User user, LocalDate startDate, LocalDate endDate) {
        List<Object[]> rows = transactionRepository.findCategoryNetBreakdown(user, startDate, endDate);
        BigDecimal grandTotal = rows.stream()
                .map(r -> ((BigDecimal) r[2]).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ReportDto.CategoryBreakdownItem> categories = rows.stream()
                .map(r -> {
                    UUID catId = r[0] != null ? UUID.fromString(r[0].toString()) : null;
                    String catName = r[1] != null ? r[1].toString() : "Senza categoria";
                    BigDecimal net = (BigDecimal) r[2];
                    double percentage = grandTotal.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : net.abs().divide(grandTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
                    TransactionType inferredType = net.compareTo(BigDecimal.ZERO) > 0 ? TransactionType.IN : TransactionType.OUT;
                    return ReportDto.CategoryBreakdownItem.builder()
                            .categoryId(catId).categoryName(catName)
                            .net(net).percentage(percentage).inferredType(inferredType)
                            .build();
                })
                .toList();

        return ReportDto.CategoryBreakdownResponse.builder()
                .startDate(startDate).endDate(endDate)
                .grandTotal(grandTotal).categories(categories)
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthComparisonResponse getMonthComparison(User user, int year, int month) {
        LocalDate currentStart = LocalDate.of(year, month, 1);
        LocalDate currentEnd = currentStart.withDayOfMonth(currentStart.lengthOfMonth());
        LocalDate prevStart = currentStart.minusMonths(1);
        LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());

        BigDecimal currIncome = transactionRepository.sumByUserAndTypeAndDateRange(user, TransactionType.IN, currentStart, currentEnd);
        BigDecimal currExpense = transactionRepository.sumByUserAndTypeAndDateRange(user, TransactionType.OUT, currentStart, currentEnd);
        BigDecimal prevIncome = transactionRepository.sumByUserAndTypeAndDateRange(user, TransactionType.IN, prevStart, prevEnd);
        BigDecimal prevExpense = transactionRepository.sumByUserAndTypeAndDateRange(user, TransactionType.OUT, prevStart, prevEnd);

        currIncome = currIncome != null ? currIncome : BigDecimal.ZERO;
        currExpense = currExpense != null ? currExpense : BigDecimal.ZERO;
        prevIncome = prevIncome != null ? prevIncome : BigDecimal.ZERO;
        prevExpense = prevExpense != null ? prevExpense : BigDecimal.ZERO;

        ReportDto.MonthComparisonItem current = ReportDto.MonthComparisonItem.builder()
                .year(year).month(month)
                .income(currIncome).expense(currExpense).net(currIncome.subtract(currExpense))
                .build();

        ReportDto.MonthComparisonItem previous = ReportDto.MonthComparisonItem.builder()
                .year(prevStart.getYear()).month(prevStart.getMonthValue())
                .income(prevIncome).expense(prevExpense).net(prevIncome.subtract(prevExpense))
                .build();

        return ReportDto.MonthComparisonResponse.builder()
                .currentMonth(current).previousMonth(previous)
                .incomeChange(currIncome.subtract(prevIncome))
                .expenseChange(currExpense.subtract(prevExpense))
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthlyProjection getMonthlyProjection(User user) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        int daysElapsed = today.getDayOfMonth();
        int daysInMonth = today.lengthOfMonth();

        BigDecimal currentExpense = transactionRepository.sumByUserAndTypeAndDateRange(
                user, TransactionType.OUT, monthStart, today);
        BigDecimal currentIncome = transactionRepository.sumByUserAndTypeAndDateRange(
                user, TransactionType.IN, monthStart, today);
        currentExpense = currentExpense != null ? currentExpense : BigDecimal.ZERO;
        currentIncome = currentIncome != null ? currentIncome : BigDecimal.ZERO;

        BigDecimal projectedExpense = daysElapsed == 0 ? BigDecimal.ZERO
                : currentExpense.divide(BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(daysInMonth));
        BigDecimal projectedIncome = daysElapsed == 0 ? BigDecimal.ZERO
                : currentIncome.divide(BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(daysInMonth));

        return ReportDto.MonthlyProjection.builder()
                .year(today.getYear()).month(today.getMonthValue())
                .currentMonthExpense(currentExpense).currentMonthIncome(currentIncome)
                .projectedMonthlyExpense(projectedExpense).projectedMonthlyIncome(projectedIncome)
                .projectionDate(today).daysElapsed(daysElapsed).daysInMonth(daysInMonth)
                .build();
    }
}
