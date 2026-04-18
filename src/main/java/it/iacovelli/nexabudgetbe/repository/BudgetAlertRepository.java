package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetAlertRepository extends JpaRepository<BudgetAlert, UUID> {
    List<BudgetAlert> findByUser(User user);
    List<BudgetAlert> findByUserAndActive(User user, Boolean active);
    List<BudgetAlert> findByActive(Boolean active);
    Optional<BudgetAlert> findByIdAndUser(UUID id, User user);
    List<BudgetAlert> findByBudget(Budget budget);
    void deleteByBudget(Budget budget);
}
