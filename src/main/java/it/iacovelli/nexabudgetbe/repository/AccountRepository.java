package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByIdAndUser(Long id, User user);
    List<Account> findByUser(User user);
    List<Account> findByUserAndType(User user, AccountType type);
    List<Account> findByUserAndCurrency(User user, String currency);
}
