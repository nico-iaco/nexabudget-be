package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

       boolean existsByImportHash(String importHash);

       @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.user = :user")
       List<Transaction> findByUser(User user);

       @Query(value = "SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.user = :user ORDER BY t.date DESC", countQuery = "SELECT COUNT(t) FROM Transaction t WHERE t.user = :user")
       Page<Transaction> findByUserPaged(@Param("user") User user, Pageable pageable);

       @Query(value = """
                     SELECT t FROM Transaction t
                     JOIN FETCH t.account a
                     LEFT JOIN FETCH t.category
                     WHERE t.user.id = :userId
                     AND (:accountId IS NULL OR t.account.id = :accountId)
                     AND (:type IS NULL OR t.type = :type)
                     AND (:categoryId IS NULL OR t.category.id = :categoryId)
                     AND (:startDate IS NULL OR t.date >= :startDate)
                     AND (:endDate IS NULL OR t.date <= :endDate)
                     AND (:searchPattern IS NULL OR LOWER(t.description) LIKE :searchPattern
                          OR LOWER(a.name) LIKE :searchPattern)
                     """, countQuery = """
                     SELECT COUNT(t) FROM Transaction t
                     LEFT JOIN t.account a
                     WHERE t.user.id = :userId
                     AND (:accountId IS NULL OR t.account.id = :accountId)
                     AND (:type IS NULL OR t.type = :type)
                     AND (:categoryId IS NULL OR t.category.id = :categoryId)
                     AND (:startDate IS NULL OR t.date >= :startDate)
                     AND (:endDate IS NULL OR t.date <= :endDate)
                     AND (:searchPattern IS NULL OR LOWER(t.description) LIKE :searchPattern
                          OR LOWER(a.name) LIKE :searchPattern)
                     """)
       Page<Transaction> findByFilters(
                     @Param("userId") UUID userId,
                     @Param("accountId") UUID accountId,
                     @Param("type") TransactionType type,
                     @Param("categoryId") UUID categoryId,
                     @Param("startDate") LocalDate startDate,
                     @Param("endDate") LocalDate endDate,
                     @Param("searchPattern") String searchPattern,
                     Pageable pageable);

       @Query("SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.account = :account")
       List<Transaction> findByAccount(Account account);

       @Query(value = "SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.account = :account ORDER BY t.date DESC", countQuery = "SELECT COUNT(t) FROM Transaction t WHERE t.account = :account")
       Page<Transaction> findByAccountPaged(@Param("account") Account account, Pageable pageable);

       @Query(value = "SELECT t FROM Transaction t JOIN FETCH t.account LEFT JOIN FETCH t.category WHERE t.account = :account AND t.date BETWEEN :start AND :end ORDER BY t.date DESC", countQuery = "SELECT COUNT(t) FROM Transaction t WHERE t.account = :account AND t.date BETWEEN :start AND :end")
       Page<Transaction> findByAccountAndDateRangePaged(@Param("account") Account account,
                     @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

       List<Transaction> findByCategoryAndUser(Category category, User user);

       List<Transaction> findByTransferIdAndUser(String transferId, User user);

       List<Transaction> findByUserAndDateBetween(User user, LocalDate start, LocalDate end);

       List<Transaction> findByAccountAndDateBetween(Account account, LocalDate start, LocalDate end);

       @Query("SELECT t FROM Transaction t WHERE t.account = :account AND t.date BETWEEN :start AND :end ORDER BY t.date DESC")
       List<Transaction> findByAccountAndDateRangeOrderByDateDesc(Account account, LocalDate start, LocalDate end);

       @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.account = :account AND t.type = :type AND t.date BETWEEN :start AND :end")
       BigDecimal sumByAccountAndTypeAndDateRange(Account account, TransactionType type, LocalDateTime start,
                     LocalDateTime end);

       void deleteAllByAccount(Account account);

       @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
                     "WHERE t.user = :user AND t.category = :category AND t.type = 'OUT' " +
                     "AND t.date BETWEEN :startDate AND :endDate")
       BigDecimal sumOutByUserAndCategoryAndDateRange(@Param("user") User user,
                     @Param("category") Category category,
                     @Param("startDate") LocalDate startDate,
                     @Param("endDate") LocalDate endDate);

       @Modifying(clearAutomatically = true, flushAutomatically = true)
       @Query("UPDATE Transaction t SET t.category = :target WHERE t.category = :source AND t.user = :user")
       int updateCategoryBulk(@Param("source") Category source, @Param("target") Category target,
                     @Param("user") User user);

       @Modifying(clearAutomatically = true, flushAutomatically = true)
       @Query(value = "UPDATE transactions SET deleted = true, deleted_at = :now WHERE id = :id AND deleted = false", nativeQuery = true)
       void softDeleteById(@Param("id") UUID id, @Param("now") LocalDateTime now);

       @Modifying(clearAutomatically = true, flushAutomatically = true)
       @Query(value = "UPDATE transactions SET deleted = true, deleted_at = :now WHERE account_id = :accountId AND deleted = false", nativeQuery = true)
       void softDeleteAllByAccountId(@Param("accountId") UUID accountId, @Param("now") LocalDateTime now);

       @Modifying(clearAutomatically = true, flushAutomatically = true)
       @Query(value = "UPDATE transactions SET deleted = false, deleted_at = null WHERE id = :id", nativeQuery = true)
       void restoreById(@Param("id") UUID id);

       @Modifying(clearAutomatically = true, flushAutomatically = true)
       @Query(value = "UPDATE transactions SET deleted = false, deleted_at = null WHERE account_id = :accountId", nativeQuery = true)
       void restoreAllByAccountId(@Param("accountId") UUID accountId);

       @Modifying(clearAutomatically = true, flushAutomatically = true)
       @Query(value = "DELETE FROM transactions", nativeQuery = true)
       void hardDeleteAll();

       @Query(value = "SELECT * FROM transactions WHERE user_id = :userId AND deleted = true ORDER BY deleted_at DESC", nativeQuery = true)
       List<Transaction> findDeletedByUserId(@Param("userId") UUID userId);

       @Query(value = "SELECT * FROM transactions WHERE id = :id AND user_id = :userId AND deleted = true", nativeQuery = true)
       Optional<Transaction> findDeletedByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

       @Modifying
       @Query(value = "DELETE FROM transactions WHERE deleted = true AND deleted_at < :cutoff", nativeQuery = true)
       int purgeOldDeleted(@Param("cutoff") LocalDateTime cutoff);

       // Report queries
       @Query("SELECT YEAR(t.date), MONTH(t.date), t.type, SUM(t.amount) " +
                     "FROM Transaction t WHERE t.user = :user AND t.date >= :from " +
                     "GROUP BY YEAR(t.date), MONTH(t.date), t.type " +
                     "ORDER BY YEAR(t.date), MONTH(t.date)")
       List<Object[]> findMonthlyTotals(@Param("user") User user, @Param("from") LocalDate from);

       @Query("SELECT t.category.id, t.category.name, SUM(t.amount) " +
                     "FROM Transaction t WHERE t.user = :user AND t.type = :type " +
                     "AND t.date BETWEEN :start AND :end AND t.category IS NOT NULL " +
                     "GROUP BY t.category.id, t.category.name " +
                     "ORDER BY SUM(t.amount) DESC")
       List<Object[]> findCategoryBreakdown(@Param("user") User user, @Param("type") TransactionType type,
                     @Param("start") LocalDate start, @Param("end") LocalDate end);

       @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
                     "WHERE t.user = :user AND t.type = :type AND t.date BETWEEN :start AND :end")
       BigDecimal sumByUserAndTypeAndDateRange(@Param("user") User user, @Param("type") TransactionType type,
                     @Param("start") LocalDate start, @Param("end") LocalDate end);
}
