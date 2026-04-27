package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Category;
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
    List<Category> findByUserIsNull();

    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.user = :user OR c.user IS NULL)")
    Optional<Category> findByIdAndUser(UUID id, User user);

    @Query("SELECT c FROM Category c WHERE (c.user = :user OR c.user IS NULL)")
    List<Category> findByUserOrDefault(User user);

    boolean existsByUserAndName(User user, String name);
}
