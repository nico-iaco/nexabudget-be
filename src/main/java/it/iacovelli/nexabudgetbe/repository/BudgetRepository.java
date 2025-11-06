package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, UUID> {
    List<Budget> findByUser(User user);
    List<Budget> findByCategory(Category category);
    List<Budget> findByUserAndCategory(User user, Category category);

    @Query("SELECT b FROM Budget b WHERE b.user = :user AND b.startDate <= :date AND (b.endDate IS NULL OR b.endDate >= :date)")
    List<Budget> findActiveBudgetsByUserAndDate(User user, LocalDate date);

    @Query("SELECT b FROM Budget b WHERE b.user = :user AND " +
            "((b.startDate BETWEEN :start AND :end) OR " +
            "(b.endDate BETWEEN :start AND :end) OR " +
            "(b.startDate <= :start AND (b.endDate IS NULL OR b.endDate >= :end)))")
    List<Budget> findBudgetsByUserAndDateRange(User user, LocalDate start, LocalDate end);
}
