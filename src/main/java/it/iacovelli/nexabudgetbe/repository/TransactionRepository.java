package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = 'IN' THEN t.amount ELSE -t.amount END), 0) FROM Transaction t WHERE t.account = :account")
    BigDecimal calculateBalanceForAccount(@Param("account") Account account);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.id = :id AND t.user = :user")
    Optional<Transaction> findByIdAndUser(UUID id, User user);

    Optional<Transaction> findByExternalId(String externalId);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.user = :user")
    List<Transaction> findByUser(User user);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.account = :account")
    List<Transaction> findByAccount(Account account);

    List<Transaction> findByCategoryAndUser(Category category, User user);
    List<Transaction> findByTransferIdAndUser(String transferId, User user);

    List<Transaction> findByUserAndDateBetween(User user, LocalDate start, LocalDate end);
    List<Transaction> findByAccountAndDateBetween(Account account, LocalDate start, LocalDate end);

    @Query("SELECT t FROM Transaction t WHERE t.account = :account AND t.date BETWEEN :start AND :end ORDER BY t.date DESC")
    List<Transaction> findByAccountAndDateRangeOrderByDateDesc(Account account, LocalDateTime start, LocalDateTime end);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.account = :account AND t.type = :type AND t.date BETWEEN :start AND :end")
    BigDecimal sumByAccountAndTypeAndDateRange(Account account, TransactionType type, LocalDateTime start, LocalDateTime end);

    void deleteAllByAccount(Account account);
}
