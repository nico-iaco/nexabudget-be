package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    @Query("SELECT ba.id FROM BudgetAlert ba WHERE ba.active = true")
    List<UUID> findActiveIds();

    @Query("""
            SELECT ba FROM BudgetAlert ba
            JOIN FETCH ba.budgetTemplate bt
            JOIN FETCH bt.category
            WHERE ba.id = :id AND ba.user = :user
            """)
    Optional<BudgetAlert> findByIdAndUser(@Param("id") UUID id, @Param("user") User user);

    @Query("""
            SELECT DISTINCT ba FROM BudgetAlert ba
            JOIN FETCH ba.budgetTemplate bt
            JOIN FETCH bt.category
            WHERE ba.user = :user AND bt.id = :templateId
            """)
    List<BudgetAlert> findByUserAndBudgetTemplateId(@Param("user") User user, @Param("templateId") UUID templateId);

    @Query("SELECT ba FROM BudgetAlert ba WHERE ba.budgetTemplate = :template")
    List<BudgetAlert> findByBudgetTemplate(@Param("template") BudgetTemplate template);

    void deleteByBudgetTemplate(BudgetTemplate template);

    // Update mirato: il job degli alert non deve sovrascrivere modifiche concorrenti dell'utente (soglia, active)
    // salvando l'entità caricata a inizio job; chiamato anche dal thread asincrono dell'email, quindi ha una tx propria.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BudgetAlert ba SET ba.lastNotifiedAt = :lastNotifiedAt WHERE ba.id = :id")
    int updateLastNotifiedAt(@Param("id") UUID id, @Param("lastNotifiedAt") LocalDateTime lastNotifiedAt);
}
