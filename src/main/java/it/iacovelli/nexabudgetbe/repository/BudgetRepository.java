package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    @Query("SELECT DISTINCT b FROM Budget b JOIN FETCH b.category WHERE b.user = :user")
    List<Budget> findByUser(@Param("user") User user);

    @Query("SELECT b FROM Budget b WHERE b.category = :category")
    List<Budget> findByCategory(@Param("category") Category category);

    @Query("SELECT DISTINCT b FROM Budget b JOIN FETCH b.category WHERE b.user = :user AND b.category = :category")
    List<Budget> findByUserAndCategory(@Param("user") User user, @Param("category") Category category);

    @Query("SELECT b FROM Budget b JOIN FETCH b.category WHERE b.id = :id AND b.user = :user")
    Optional<Budget> findByIdAndUser(@Param("id") UUID id, @Param("user") User user);

    @Query("SELECT b FROM Budget b JOIN FETCH b.category WHERE b.id = :id AND b.user.id = :userId")
    Optional<Budget> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT b FROM Budget b JOIN FETCH b.category WHERE b.user = :user AND b.startDate <= :date AND (b.endDate IS NULL OR b.endDate >= :date)")
    List<Budget> findActiveBudgetsByUserAndDate(@Param("user") User user, @Param("date") LocalDate date);

    @Query("SELECT b FROM Budget b JOIN FETCH b.category WHERE b.user = :user AND b.category = :category AND b.startDate <= :date AND (b.endDate IS NULL OR b.endDate >= :date)")
    Optional<Budget> findActiveBudgetByUserAndCategoryAndDate(@Param("user") User user, @Param("category") Category category, @Param("date") LocalDate date);

    @Query("SELECT b FROM Budget b JOIN FETCH b.category WHERE b.user = :user AND " +
            "((b.startDate BETWEEN :start AND :end) OR " +
            "(b.endDate BETWEEN :start AND :end) OR " +
            "(b.startDate <= :start AND (b.endDate IS NULL OR b.endDate >= :end)))")
    List<Budget> findBudgetsByUserAndDateRange(@Param("user") User user, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Budget b SET b.category = :target WHERE b.category = :source AND b.user = :user")
    int updateCategoryBulk(@Param("source") Category source, @Param("target") Category target, @Param("user") User user);
}

