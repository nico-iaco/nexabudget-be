package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetDto;
import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
public class BudgetService {
    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public BudgetService(BudgetRepository budgetRepository, TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    public Budget createBudget(Budget budget) {
        validateBudgetDates(budget);
        return budgetRepository.save(budget);
    }

    public Optional<Budget> getBudgetById(UUID id) {
        return budgetRepository.findById(id);
    }

    public Optional<Budget> getBudgetByIdAndUser(UUID id, User user) {
        return budgetRepository.findByIdAndUser(id, user);
    }

    public Optional<Budget> getBudgetByIdAndUserId(UUID id, UUID userId) {
        return budgetRepository.findByIdAndUserId(id, userId);
    }

    public List<Budget> getAllBudgets() {
        return budgetRepository.findAll();
    }

    public List<Budget> getBudgetsByUser(User user) {
        return budgetRepository.findByUser(user);
    }

    public List<Budget> getBudgetsByCategory(Category category) {
        return budgetRepository.findByCategory(category);
    }

    public List<Budget> getBudgetsByUserAndCategory(User user, Category category) {
        return budgetRepository.findByUserAndCategory(user, category);
    }

    public List<Budget> getActiveBudgets(User user, LocalDate date) {
        return budgetRepository.findActiveBudgetsByUserAndDate(user, date);
    }

    public List<Budget> getBudgetsByDateRange(User user, LocalDate start, LocalDate end) {
        return budgetRepository.findBudgetsByUserAndDateRange(user, start, end);
    }

    public Budget updateBudget(Budget budget) {
        validateBudgetDates(budget);
        return budgetRepository.save(budget);
    }

    private void validateBudgetDates(Budget budget) {
        if (budget.getEndDate() != null && budget.getEndDate().isBefore(budget.getStartDate())) {
            throw new IllegalArgumentException(
                    "La data di fine budget non può essere precedente alla data di inizio");
        }
    }

    public void deleteBudget(UUID budgetId) {
        budgetRepository.deleteById(budgetId);
    }

    @Transactional(readOnly = true)
    public Map<Budget, BigDecimal> getBudgetUsage(User user, LocalDate date) {
        List<Budget> activeBudgets = getActiveBudgets(user, date);
        Map<Budget, BigDecimal> budgetUsage = new HashMap<>();

        LocalDate startOfMonth = date.withDayOfMonth(1);
        LocalDate endOfMonth = date.withDayOfMonth(date.lengthOfMonth());

        for (Budget budget : activeBudgets) {
            Category category = budget.getCategory();
            BigDecimal spent = transactionRepository.sumNetByUserAndCategoryAndDateRange(
                    user, category, startOfMonth, endOfMonth);
            budgetUsage.put(budget, spent != null ? spent : BigDecimal.ZERO);
        }

        return budgetUsage;
    }

    @Transactional(readOnly = true)
    public Map<Budget, BigDecimal> getRemainingBudgets(User user, LocalDate date) {
        Map<Budget, BigDecimal> budgetUsage = getBudgetUsage(user, date);
        Map<Budget, BigDecimal> remainingBudgets = new HashMap<>();

        budgetUsage.forEach((budget, spent) -> {
            BigDecimal remaining = budget.getBudgetLimit().subtract(spent);
            remainingBudgets.put(budget, remaining);
        });

        return remainingBudgets;
    }

    @Transactional(readOnly = true)
    public List<BudgetDto.MonthlySummaryResponse> getBudgetMonthlySummary(User user, LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now();
        LocalDate periodStart = targetDate.withDayOfMonth(1);
        LocalDate periodEnd = targetDate.withDayOfMonth(targetDate.lengthOfMonth());

        Map<Budget, BigDecimal> budgetUsage = getBudgetUsage(user, targetDate);

        return budgetUsage.entrySet().stream()
                .map(entry -> {
                    Budget budget = entry.getKey();
                    BigDecimal spent = entry.getValue();
                    BigDecimal remaining = budget.getBudgetLimit().subtract(spent);
                    double percentageUsed = budget.getBudgetLimit().compareTo(BigDecimal.ZERO) == 0
                            ? 0.0
                            : spent.multiply(BigDecimal.valueOf(100))
                                    .divide(budget.getBudgetLimit(), 2, RoundingMode.HALF_UP)
                                    .doubleValue();

                    return BudgetDto.MonthlySummaryResponse.builder()
                            .budgetId(budget.getId())
                            .categoryId(budget.getCategory().getId())
                            .categoryName(budget.getCategory().getName())
                            .limit(budget.getBudgetLimit())
                            .spent(spent)
                            .remaining(remaining)
                            .percentageUsed(percentageUsed)
                            .budgetStartDate(budget.getStartDate())
                            .budgetEndDate(budget.getEndDate())
                            .periodStart(periodStart)
                            .periodEnd(periodEnd)
                            .build();
                })
                .toList();
    }
}
