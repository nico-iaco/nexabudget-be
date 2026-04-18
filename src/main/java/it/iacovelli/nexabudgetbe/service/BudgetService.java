package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
            BigDecimal spent = transactionRepository.sumOutByUserAndCategoryAndDateRange(
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
}
