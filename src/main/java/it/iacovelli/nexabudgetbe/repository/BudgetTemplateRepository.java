package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.RecurrenceType;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetTemplateRepository extends JpaRepository<BudgetTemplate, UUID> {
    List<BudgetTemplate> findByUser(User user);
    List<BudgetTemplate> findByUserAndActive(User user, Boolean active);
    List<BudgetTemplate> findByActiveAndRecurrenceType(Boolean active, RecurrenceType recurrenceType);
    Optional<BudgetTemplate> findByIdAndUser(UUID id, User user);
}
