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
    private final CurrencyConversionService currencyConversionService;

    public ReportService(TransactionRepository transactionRepository,
                         CurrencyConversionService currencyConversionService) {
        this.transactionRepository = transactionRepository;
        this.currencyConversionService = currencyConversionService;
    }

    private String targetCurrency(User user) {
        return user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR";
    }

    private BigDecimal convertToUserCurrency(BigDecimal amount, String sourceCurrency, String target) {
        if (amount == null) return BigDecimal.ZERO;
        String src = sourceCurrency != null && !sourceCurrency.isBlank() ? sourceCurrency : target;
        return currencyConversionService.convert(amount, src, target);
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthlyTrendResponse getMonthlyTrend(User user, int months) {
        String target = targetCurrency(user);
        LocalDate from = LocalDate.now().minusMonths(months).withDayOfMonth(1);
        List<Object[]> rows = transactionRepository.findMonthlyTotals(user, from);

        // Each row: [year, month, type, currency, total]
        Map<String, ReportDto.MonthlyTrendItem> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            TransactionType type = TransactionType.valueOf(row[2].toString());
            String currency = row[3] != null ? row[3].toString() : target;
            BigDecimal total = (BigDecimal) row[4];
            BigDecimal converted = convertToUserCurrency(total, currency, target);

            String key = year + "-" + month;
            ReportDto.MonthlyTrendItem item = map.computeIfAbsent(key, k ->
                    ReportDto.MonthlyTrendItem.builder()
                            .year(year).month(month)
                            .income(BigDecimal.ZERO).expense(BigDecimal.ZERO).net(BigDecimal.ZERO)
                            .build());

            if (type == TransactionType.IN) {
                item.setIncome(item.getIncome().add(converted));
            } else {
                item.setExpense(item.getExpense().add(converted));
            }
            item.setNet(item.getIncome().subtract(item.getExpense()));
        }

        return ReportDto.MonthlyTrendResponse.builder()
                .currency(target)
                .items(new ArrayList<>(map.values()))
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.CategoryBreakdownResponse getCategoryBreakdown(User user, LocalDate startDate, LocalDate endDate) {
        String target = targetCurrency(user);
        List<Object[]> rows = transactionRepository.findCategoryNetBreakdown(user, startDate, endDate);

        // Each row: [categoryId, categoryName, currency, net]
        // Aggregate by category (sum converted nets across currencies).
        record CatKey(UUID id, String name, TransactionType forcedType) {}
        Map<CatKey, BigDecimal> aggregatedNet = new LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID catId = r[0] != null ? UUID.fromString(r[0].toString()) : null;
            String catName = r[1] != null ? r[1].toString() : "n/a";
            String currency = r[2] != null ? r[2].toString() : target;
            BigDecimal net = (BigDecimal) r[3];
            BigDecimal converted = convertToUserCurrency(net, currency, target);
            aggregatedNet.merge(new CatKey(catId, catName, null), converted, BigDecimal::add);
        }

        // Senza categoria: due bucket separati per IN e OUT (no netting)
        for (Object[] r : transactionRepository.findUncategorizedTotalsByType(user, startDate, endDate)) {
            String currency = r[0] != null ? r[0].toString() : target;
            TransactionType type = TransactionType.valueOf(r[1].toString());
            BigDecimal amount = (BigDecimal) r[2];
            BigDecimal converted = convertToUserCurrency(amount, currency, target);
            // Per coerenza con il netting: OUT positivo, IN negativo
            BigDecimal signed = type == TransactionType.OUT ? converted : converted.negate();
            aggregatedNet.merge(new CatKey(null, "n/a", type), signed, BigDecimal::add);
        }

        BigDecimal outGroupTotal = aggregatedNet.values().stream()
                .filter(n -> n.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal inGroupTotal = aggregatedNet.values().stream()
                .filter(n -> n.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ReportDto.CategoryBreakdownItem> categories = aggregatedNet.entrySet().stream()
                .filter(e -> e.getValue().compareTo(BigDecimal.ZERO) != 0)
                .sorted((a, b) -> b.getValue().abs().compareTo(a.getValue().abs()))
                .map(e -> {
                    BigDecimal net = e.getValue();
                    TransactionType inferredType = e.getKey().forcedType() != null
                            ? e.getKey().forcedType()
                            : (net.compareTo(BigDecimal.ZERO) > 0 ? TransactionType.OUT : TransactionType.IN);
                    BigDecimal groupTotal = inferredType == TransactionType.OUT ? outGroupTotal : inGroupTotal;
                    double percentage = groupTotal.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : net.abs().divide(groupTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
                    return ReportDto.CategoryBreakdownItem.builder()
                            .categoryId(e.getKey().id()).categoryName(e.getKey().name())
                            .net(net.abs()).percentage(percentage).inferredType(inferredType)
                            .build();
                })
                .toList();

        return ReportDto.CategoryBreakdownResponse.builder()
                .startDate(startDate).endDate(endDate)
                .currency(target)
                .grandTotal(outGroupTotal.add(inGroupTotal)).categories(categories)
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthComparisonResponse getMonthComparison(User user, int year, int month) {
        String target = targetCurrency(user);
        LocalDate currentStart = LocalDate.of(year, month, 1);
        LocalDate currentEnd = currentStart.withDayOfMonth(currentStart.lengthOfMonth());
        LocalDate prevStart = currentStart.minusMonths(1);
        LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());

        BigDecimal currIncome = sumConverted(user, TransactionType.IN, currentStart, currentEnd, target);
        BigDecimal currExpense = sumConverted(user, TransactionType.OUT, currentStart, currentEnd, target);
        BigDecimal prevIncome = sumConverted(user, TransactionType.IN, prevStart, prevEnd, target);
        BigDecimal prevExpense = sumConverted(user, TransactionType.OUT, prevStart, prevEnd, target);

        ReportDto.MonthComparisonItem current = ReportDto.MonthComparisonItem.builder()
                .year(year).month(month)
                .income(currIncome).expense(currExpense).net(currIncome.subtract(currExpense))
                .build();

        ReportDto.MonthComparisonItem previous = ReportDto.MonthComparisonItem.builder()
                .year(prevStart.getYear()).month(prevStart.getMonthValue())
                .income(prevIncome).expense(prevExpense).net(prevIncome.subtract(prevExpense))
                .build();

        return ReportDto.MonthComparisonResponse.builder()
                .currency(target)
                .currentMonth(current).previousMonth(previous)
                .incomeChange(currIncome.subtract(prevIncome))
                .expenseChange(currExpense.subtract(prevExpense))
                .build();
    }

    private BigDecimal sumConverted(User user, TransactionType type, LocalDate start, LocalDate end, String target) {
        List<Object[]> rows = transactionRepository.sumByUserAndTypeAndDateRangePerCurrency(user, type, start, end);
        BigDecimal sum = BigDecimal.ZERO;
        for (Object[] row : rows) {
            String currency = row[0] != null ? row[0].toString() : target;
            BigDecimal amount = (BigDecimal) row[1];
            sum = sum.add(convertToUserCurrency(amount, currency, target));
        }
        return sum;
    }

    @Transactional(readOnly = true)
    public ReportDto.BalanceTrendResponse getBalanceTrend(User user, LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
        String target = targetCurrency(user);

        LocalDate rangeStart = startDate.withDayOfMonth(1);
        LocalDate rangeEnd = endDate.withDayOfMonth(endDate.lengthOfMonth());

        // Opening balance: sum (IN-OUT) before rangeStart, per currency, then convert.
        BigDecimal opening = BigDecimal.ZERO;
        for (Object[] row : transactionRepository.sumNetByUserBeforePerCurrency(user, rangeStart)) {
            String currency = row[0] != null ? row[0].toString() : target;
            BigDecimal amount = (BigDecimal) row[1];
            opening = opening.add(convertToUserCurrency(amount, currency, target));
        }

        List<Object[]> rows = transactionRepository.findMonthlyNetTotals(user, rangeStart, rangeEnd);
        // Each row: [year, month, currency, net]
        Map<String, BigDecimal> netByMonth = new LinkedHashMap<>();
        for (Object[] row : rows) {
            int y = ((Number) row[0]).intValue();
            int m = ((Number) row[1]).intValue();
            String currency = row[2] != null ? row[2].toString() : target;
            BigDecimal net = (BigDecimal) row[3];
            BigDecimal converted = convertToUserCurrency(net, currency, target);
            netByMonth.merge(y + "-" + m, converted, BigDecimal::add);
        }

        List<ReportDto.BalanceTrendItem> items = new ArrayList<>();
        BigDecimal running = opening;
        LocalDate cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            int y = cursor.getYear();
            int m = cursor.getMonthValue();
            BigDecimal net = netByMonth.getOrDefault(y + "-" + m, BigDecimal.ZERO);
            running = running.add(net);
            items.add(ReportDto.BalanceTrendItem.builder()
                    .year(y).month(m)
                    .monthlyNet(net).closingBalance(running)
                    .build());
            cursor = cursor.plusMonths(1);
        }

        return ReportDto.BalanceTrendResponse.builder()
                .startDate(rangeStart).endDate(rangeEnd)
                .currency(target)
                .openingBalance(opening).items(items)
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthlyProjection getMonthlyProjection(User user) {
        String target = targetCurrency(user);
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        int daysElapsed = today.getDayOfMonth();
        int daysInMonth = today.lengthOfMonth();

        BigDecimal currentExpense = sumConverted(user, TransactionType.OUT, monthStart, today, target);
        BigDecimal currentIncome = sumConverted(user, TransactionType.IN, monthStart, today, target);

        BigDecimal totalHistoricExpense = BigDecimal.ZERO;
        BigDecimal totalHistoricIncome = BigDecimal.ZERO;
        int monthsWithData = 0;

        for (int i = 1; i <= 3; i++) {
            LocalDate refMonthStart = monthStart.minusMonths(i);
            LocalDate refMonthEnd = refMonthStart.withDayOfMonth(refMonthStart.lengthOfMonth());

            BigDecimal monthExpense = sumConverted(user, TransactionType.OUT, refMonthStart, refMonthEnd, target);
            BigDecimal monthIncome = sumConverted(user, TransactionType.IN, refMonthStart, refMonthEnd, target);

            if (monthExpense.compareTo(BigDecimal.ZERO) > 0 || monthIncome.compareTo(BigDecimal.ZERO) > 0) {
                totalHistoricExpense = totalHistoricExpense.add(monthExpense);
                totalHistoricIncome = totalHistoricIncome.add(monthIncome);
                monthsWithData++;
            }
        }

        BigDecimal projectedExpense;
        BigDecimal projectedIncome;

        if (monthsWithData > 0) {
            projectedExpense = totalHistoricExpense.divide(BigDecimal.valueOf(monthsWithData), 4, RoundingMode.HALF_UP);
            projectedIncome = totalHistoricIncome.divide(BigDecimal.valueOf(monthsWithData), 4, RoundingMode.HALF_UP);
        } else {
            // fallback: daily rate del mese corrente se non c'è storico
            projectedExpense = daysElapsed == 0 ? BigDecimal.ZERO
                    : currentExpense.divide(BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(daysInMonth));
            projectedIncome = daysElapsed == 0 ? BigDecimal.ZERO
                    : currentIncome.divide(BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(daysInMonth));
        }

        return ReportDto.MonthlyProjection.builder()
                .year(today.getYear()).month(today.getMonthValue())
                .currency(target)
                .currentMonthExpense(currentExpense).currentMonthIncome(currentIncome)
                .projectedMonthlyExpense(projectedExpense).projectedMonthlyIncome(projectedIncome)
                .projectionDate(today).daysElapsed(daysElapsed).daysInMonth(daysInMonth)
                .build();
    }
}
