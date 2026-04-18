package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByIdAndUser(UUID id, User user);
    List<Account> findByUser(User user);
    List<Account> findByUserAndType(User user, AccountType type);
    List<Account> findByUserAndCurrency(User user, String currency);

    @Modifying
    @Query("UPDATE Account a SET a.isSynchronizing = true WHERE a.id = :id AND a.isSynchronizing = false")
    int markSynchronizing(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE accounts SET deleted = true, deleted_at = :now WHERE id = :id AND deleted = false", nativeQuery = true)
    void softDeleteById(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE accounts SET deleted = false, deleted_at = null WHERE id = :id", nativeQuery = true)
    void restoreById(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM accounts", nativeQuery = true)
    void hardDeleteAll();

    @Query(value = "SELECT * FROM accounts WHERE user_id = :userId AND deleted = true ORDER BY deleted_at DESC", nativeQuery = true)
    List<Account> findDeletedByUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT * FROM accounts WHERE id = :id AND user_id = :userId AND deleted = true", nativeQuery = true)
    Optional<Account> findDeletedByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM accounts WHERE deleted = true AND deleted_at < :cutoff", nativeQuery = true)
    int purgeOldDeleted(@Param("cutoff") LocalDateTime cutoff);
}
