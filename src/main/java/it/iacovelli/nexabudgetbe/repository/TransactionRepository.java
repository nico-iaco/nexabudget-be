package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.Transaction;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = 'IN' THEN t.importo ELSE -t.importo END), 0) FROM Transaction t WHERE t.account = :account")
    BigDecimal calculateBalanceForAccount(@Param("account") Account account);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.id = :id AND t.user = :user")
    Optional<Transaction> findByIdAndUser(Long id, User user);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.user = :user")
    List<Transaction> findByUser(User user);

    @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.account = :account")
    List<Transaction> findByAccount(Account account);

    List<Transaction> findByCategoryAndUser(Category category, User user);
    List<Transaction> findByTransferIdAndUser(String transferId, User user);

    List<Transaction> findByUserAndDataBetween(User user, LocalDate start, LocalDate end);
    List<Transaction> findByAccountAndDataBetween(Account account, LocalDate start, LocalDate end);

    @Query("SELECT t FROM Transaction t WHERE t.account = :account AND t.data BETWEEN :start AND :end ORDER BY t.data DESC")
    List<Transaction> findByAccountAndDateRangeOrderByDateDesc(Account account, LocalDateTime start, LocalDateTime end);

    @Query("SELECT SUM(t.importo) FROM Transaction t WHERE t.account = :account AND t.type = :type AND t.data BETWEEN :start AND :end")
    BigDecimal sumByAccountAndTypeAndDateRange(Account account, TransactionType type, LocalDateTime start, LocalDateTime end);

    void deleteAllByAccount(Account account);
}
