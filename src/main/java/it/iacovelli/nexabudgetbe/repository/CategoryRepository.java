package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByUser(User user);
    List<Category> findByUserIsNull(); // Categorie predefinite
    List<Category> findByUserAndTransactionType(User user, TransactionType type);

    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.user = :user OR c.user IS NULL)")
    Optional<Category> findByIdAndUser(UUID id, User user);

    @Query("SELECT c FROM Category c WHERE (c.user = :user OR c.user IS NULL) AND c.transactionType = :type")
    List<Category> findByUserOrDefaultAndTransactionType(User user, TransactionType type);

    @Query("SELECT c FROM Category c WHERE (c.user = :user OR c.user IS NULL)")
    List<Category> findByUserOrDefault(User user);
}
