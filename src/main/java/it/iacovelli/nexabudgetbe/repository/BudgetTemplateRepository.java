package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.RecurrenceType;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetTemplateRepository extends JpaRepository<BudgetTemplate, UUID> {

    @Query("SELECT DISTINCT bt FROM BudgetTemplate bt JOIN FETCH bt.category WHERE bt.user = :user")
    List<BudgetTemplate> findByUser(@Param("user") User user);

    @Query("SELECT DISTINCT bt FROM BudgetTemplate bt JOIN FETCH bt.category WHERE bt.user = :user AND bt.active = :active")
    List<BudgetTemplate> findByUserAndActive(@Param("user") User user, @Param("active") Boolean active);

    @Query("SELECT DISTINCT bt FROM BudgetTemplate bt JOIN FETCH bt.category WHERE bt.active = :active AND bt.recurrenceType = :recurrenceType")
    List<BudgetTemplate> findByActiveAndRecurrenceType(@Param("active") Boolean active, @Param("recurrenceType") RecurrenceType recurrenceType);

    @Query("SELECT bt FROM BudgetTemplate bt JOIN FETCH bt.category WHERE bt.id = :id AND bt.user = :user")
    Optional<BudgetTemplate> findByIdAndUser(@Param("id") UUID id, @Param("user") User user);
}

