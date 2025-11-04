package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BudgetService {
    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public BudgetService(BudgetRepository budgetRepository, TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    public Budget createBudget(Budget budget) {
        return budgetRepository.save(budget);
    }

    public Optional<Budget> getBudgetById(Long id) {
        return budgetRepository.findById(id);
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
        return budgetRepository.save(budget);
    }

    public void deleteBudget(Long budgetId) {
        budgetRepository.deleteById(budgetId);
    }

    public Map<Budget, BigDecimal> getBudgetUsage(User user, LocalDate date) {
        List<Budget> activeBudgets = getActiveBudgets(user, date);
        Map<Budget, BigDecimal> budgetUsage = new HashMap<>();

        LocalDate startOfMonth = date.withDayOfMonth(1);
        LocalDate endOfMonth = date.withDayOfMonth(date.lengthOfMonth());

        for (Budget budget : activeBudgets) {
            Category category = budget.getCategory();

            // Trova tutte le transazioni di tipo OUT per questa categoria in questo periodo
            List<Transaction> transactions = transactionRepository.findByUserAndDateBetween(user, startOfMonth, endOfMonth)
                    .stream()
                    .filter(t -> t.getType() == TransactionType.OUT && category.equals(t.getCategory()))
                    .toList();

            BigDecimal spent = transactions.stream()
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            budgetUsage.put(budget, spent);
        }

        return budgetUsage;
    }

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
