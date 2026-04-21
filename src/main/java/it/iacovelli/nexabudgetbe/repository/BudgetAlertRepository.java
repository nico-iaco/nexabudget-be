package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetAlertRepository extends JpaRepository<BudgetAlert, UUID> {

    @Query("""
            SELECT DISTINCT ba FROM BudgetAlert ba
            JOIN FETCH ba.budgetTemplate bt
            JOIN FETCH bt.category
            WHERE ba.user = :user
            """)
    List<BudgetAlert> findByUser(@Param("user") User user);

    @Query("""
            SELECT DISTINCT ba FROM BudgetAlert ba
            JOIN FETCH ba.budgetTemplate bt
            JOIN FETCH bt.category
            WHERE ba.active = :active
            """)
    List<BudgetAlert> findByActive(@Param("active") Boolean active);

    @Query("""
            SELECT ba FROM BudgetAlert ba
            JOIN FETCH ba.budgetTemplate bt
            JOIN FETCH bt.category
            WHERE ba.id = :id AND ba.user = :user
            """)
    Optional<BudgetAlert> findByIdAndUser(@Param("id") UUID id, @Param("user") User user);

    @Query("SELECT ba FROM BudgetAlert ba WHERE ba.budgetTemplate = :template")
    List<BudgetAlert> findByBudgetTemplate(@Param("template") BudgetTemplate template);

    void deleteByBudgetTemplate(BudgetTemplate template);
}
