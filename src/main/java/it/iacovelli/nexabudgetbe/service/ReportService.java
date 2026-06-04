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

    private record NetTotals(BigDecimal expense, BigDecimal income) {
        BigDecimal net() { return income.subtract(expense); }
    }

    /**
     * Aggrega il netto per-categoria in expense/income, stessa logica di getCategoryBreakdown.
     * catRows: [catId, catName, currency, net]  (da findCategoryNetBreakdown)
     * uncatRows: [currency, type, amount]        (da findUncategorizedTotalsByType)
     */
    private NetTotals aggregateNetTotals(List<Object[]> catRows, List<Object[]> uncatRows, String target) {
        Map<String, BigDecimal> netByCat = new LinkedHashMap<>();
        for (Object[] r : catRows) {
            String catKey = r[0] != null ? r[0].toString() : "__null__";
            String currency = r[2] != null ? r[2].toString() : target;
            BigDecimal converted = convertToUserCurrency((BigDecimal) r[3], currency, target);
            netByCat.merge(catKey, converted, BigDecimal::add);
        }
        BigDecimal expense = netByCat.values().stream()
                .filter(n -> n.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal income = netByCat.values().stream()
                .filter(n -> n.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Object[] r : uncatRows) {
            String currency = r[0] != null ? r[0].toString() : target;
            TransactionType type = TransactionType.valueOf(r[1].toString());
            BigDecimal converted = convertToUserCurrency((BigDecimal) r[2], currency, target);
            if (type == TransactionType.OUT) expense = expense.add(converted);
            else income = income.add(converted);
        }
        return new NetTotals(expense, income);
    }

    /**
     * Costruisce una mappa year*12+month → MonthlyTrendItem usando la logica netta per-categoria.
     * Usa le query findMonthlyCategoryNetBreakdown e findMonthlyUncategorizedTotalsByType.
     */
    private Map<Integer, ReportDto.MonthlyTrendItem> buildMonthlyNetMap(User user, LocalDate from, LocalDate to, String target) {
        List<Object[]> catRows = transactionRepository.findMonthlyCategoryNetBreakdown(user, from, to);
        List<Object[]> uncatRows = transactionRepository.findMonthlyUncategorizedTotalsByType(user, from, to);

        // catRows: [year, month, catId, currency, net]
        Map<Integer, Map<String, BigDecimal>> netByCatByMonth = new TreeMap<>();
        for (Object[] r : catRows) {
            int y = ((Number) r[0]).intValue(), m = ((Number) r[1]).intValue();
            int key = y * 12 + m;
            String catKey = r[2] != null ? r[2].toString() : "__null__";
            String currency = r[3] != null ? r[3].toString() : target;
            BigDecimal converted = convertToUserCurrency((BigDecimal) r[4], currency, target);
            netByCatByMonth.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .merge(catKey, converted, BigDecimal::add);
        }

        // uncatRows: [year, month, currency, type, amount] — no netting, bucket separati
        Map<Integer, List<Object[]>> uncatByMonth = new TreeMap<>();
        for (Object[] r : uncatRows) {
            int y = ((Number) r[0]).intValue(), m = ((Number) r[1]).intValue();
            uncatByMonth.computeIfAbsent(y * 12 + m, k -> new ArrayList<>())
                    .add(new Object[]{r[2], r[3], r[4]});
        }

        Set<Integer> allKeys = new TreeSet<>(netByCatByMonth.keySet());
        allKeys.addAll(uncatByMonth.keySet());

        Map<Integer, ReportDto.MonthlyTrendItem> result = new TreeMap<>();
        for (int key : allKeys) {
            int y = (key - 1) / 12, m = key - y * 12;

            Map<String, BigDecimal> netByCat = netByCatByMonth.getOrDefault(key, Map.of());
            BigDecimal expense = netByCat.values().stream()
                    .filter(n -> n.compareTo(BigDecimal.ZERO) > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal income = netByCat.values().stream()
                    .filter(n -> n.compareTo(BigDecimal.ZERO) < 0)
                    .map(BigDecimal::abs)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            for (Object[] r : uncatByMonth.getOrDefault(key, List.of())) {
                String currency = r[0] != null ? r[0].toString() : target;
                TransactionType type = TransactionType.valueOf(r[1].toString());
                BigDecimal converted = convertToUserCurrency((BigDecimal) r[2], currency, target);
                if (type == TransactionType.OUT) expense = expense.add(converted);
                else income = income.add(converted);
            }

            result.put(key, ReportDto.MonthlyTrendItem.builder()
                    .year(y).month(m)
                    .income(income).expense(expense).net(income.subtract(expense))
                    .build());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthlyTrendResponse getMonthlyTrend(User user, int months) {
        String target = targetCurrency(user);
        LocalDate from = LocalDate.now().minusMonths(months).withDayOfMonth(1);
        LocalDate to = LocalDate.now();
        Map<Integer, ReportDto.MonthlyTrendItem> map = buildMonthlyNetMap(user, from, to, target);
        return ReportDto.MonthlyTrendResponse.builder()
                .currency(target)
                .items(new ArrayList<>(map.values()))
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthlyTrendResponse getMonthlyTrendByRange(User user, LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
        String target = targetCurrency(user);
        LocalDate rangeStart = startDate.withDayOfMonth(1);
        LocalDate rangeEnd = endDate.withDayOfMonth(endDate.lengthOfMonth());

        Map<Integer, ReportDto.MonthlyTrendItem> map = buildMonthlyNetMap(user, rangeStart, rangeEnd, target);

        // Riempie i mesi senza transazioni con zeri
        List<ReportDto.MonthlyTrendItem> items = new ArrayList<>();
        LocalDate cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            int key = cursor.getYear() * 12 + cursor.getMonthValue();
            items.add(map.getOrDefault(key, ReportDto.MonthlyTrendItem.builder()
                    .year(cursor.getYear()).month(cursor.getMonthValue())
                    .income(BigDecimal.ZERO).expense(BigDecimal.ZERO).net(BigDecimal.ZERO)
                    .build()));
            cursor = cursor.plusMonths(1);
        }

        return ReportDto.MonthlyTrendResponse.builder()
                .currency(target)
                .items(items)
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.CategoryBreakdownResponse getCategoryBreakdown(User user, LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
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
                // Includi tutte le categorie che hanno avuto movimenti nel periodo, anche se il net è 0
                // (es. spese interamente rimborsate). Solo le voci senza alcun movimento vengono scartate.
                .sorted((a, b) -> b.getValue().abs().compareTo(a.getValue().abs()))
                .map(e -> {
                    BigDecimal net = e.getValue();
                    // Categoria con net == 0: mantenuta nell'elenco (tipo OUT per default, spesa rimborsata).
                    TransactionType inferredType = e.getKey().forcedType() != null
                            ? e.getKey().forcedType()
                            : (net.compareTo(BigDecimal.ZERO) >= 0 ? TransactionType.OUT : TransactionType.IN);
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
                .totalExpense(outGroupTotal)
                .totalIncome(inGroupTotal)
                .grandTotal(outGroupTotal)  // retro-compatibilità: valorizzato come totalExpense
                .categories(categories)
                .build();
    }

    @Transactional(readOnly = true)
    public ReportDto.MonthComparisonResponse getMonthComparison(User user, int year, int month) {
        String target = targetCurrency(user);
        LocalDate currentStart = LocalDate.of(year, month, 1);
        LocalDate currentEnd = currentStart.withDayOfMonth(currentStart.lengthOfMonth());
        LocalDate prevStart = currentStart.minusMonths(1);
        LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());

        NetTotals current = aggregateNetTotals(
                transactionRepository.findCategoryNetBreakdown(user, currentStart, currentEnd),
                transactionRepository.findUncategorizedTotalsByType(user, currentStart, currentEnd),
                target);
        NetTotals previous = aggregateNetTotals(
                transactionRepository.findCategoryNetBreakdown(user, prevStart, prevEnd),
                transactionRepository.findUncategorizedTotalsByType(user, prevStart, prevEnd),
                target);

        ReportDto.MonthComparisonItem currentItem = ReportDto.MonthComparisonItem.builder()
                .year(year).month(month)
                .income(current.income()).expense(current.expense()).net(current.net())
                .build();
        ReportDto.MonthComparisonItem previousItem = ReportDto.MonthComparisonItem.builder()
                .year(prevStart.getYear()).month(prevStart.getMonthValue())
                .income(previous.income()).expense(previous.expense()).net(previous.net())
                .build();

        return ReportDto.MonthComparisonResponse.builder()
                .currency(target)
                .currentMonth(currentItem).previousMonth(previousItem)
                .incomeChange(current.income().subtract(previous.income()))
                .expenseChange(current.expense().subtract(previous.expense()))
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
