package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public AccountDto.AccountResponse createAccount(Account account, BigDecimal starterBalance) {
        Account savedAccount = accountRepository.save(account);

        // Se il saldo iniziale è diverso da zero, crea la transazione
        if (starterBalance != null && starterBalance.compareTo(BigDecimal.ZERO) != 0) {
            Transaction initialTransaction = Transaction.builder()
                    .user(account.getUser())
                    .account(savedAccount)
                    .importo(starterBalance.abs()) // Usa il valore assoluto
                    .type(starterBalance.compareTo(BigDecimal.ZERO) >= 0 ? TransactionType.IN : TransactionType.OUT)
                    .descrizione("Saldo iniziale")
                    .data(LocalDate.now())
                    .build();

            // Salva la transazione
            transactionRepository.save(initialTransaction);
        }

        return mapAccountToDto(savedAccount);
    }

    public Optional<Account> getAccountById(long id) {
        return accountRepository.findById(id);
    }

    public Optional<AccountDto.AccountResponse> getAccountByIdAndUser(Long id, User user) {
        return accountRepository.findByIdAndUser(id, user)
                .map(this::mapAccountToDto);
    }

    // NUOVO METODO: Restituisce l'entità Account, necessaria per le operazioni interne
    public Optional<Account> getAccountEntityByIdAndUser(Long id, User user) {
        return accountRepository.findByIdAndUser(id, user);
    }

    public List<AccountDto.AccountResponse> getAccountsByUser(User user) {
        return accountRepository.findByUser(user)
                .stream()
                .map(this::mapAccountToDto)
                .toList();
    }

    public List<AccountDto.AccountResponse> getAccountsByUserAndType(User user, AccountType type) {
        return accountRepository.findByUserAndType(user, type)
                .stream()
                .map(this::mapAccountToDto)
                .toList();
    }

    public List<AccountDto.AccountResponse> getAccountsByUserAndCurrency(User user, String currency) {
        return accountRepository.findByUserAndCurrency(user, currency)
                .stream()
                .map(this::mapAccountToDto)
                .toList();
    }

    @Transactional
    public AccountDto.AccountResponse updateAccount(Account account, String newName, AccountType newType, String newCurrency) {
        account.setName(newName);
        account.setType(newType);
        account.setCurrency(newCurrency);
        Account updatedAccount = accountRepository.save(account);
        return mapAccountToDto(updatedAccount);
    }

    @Transactional
    public void deleteAccount(Long accountId) {
        // Trova il conto da eliminare
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));

        // Elimina tutte le transazioni associate a questo conto
        transactionRepository.deleteAllByAccount(account);

        // Ora elimina il conto
        accountRepository.delete(account);
    }

    public BigDecimal getTotalBalance(User user, String currency) {
        List<Account> accounts = accountRepository.findByUserAndCurrency(user, currency);
        BigDecimal totalBalance = BigDecimal.ZERO;

        for (Account account : accounts) {
            BigDecimal balance = transactionRepository.calculateBalanceForAccount(account);
            totalBalance = totalBalance.add(balance);
        }

        return totalBalance;
    }

    // Metodo helper per mappare Entità a DTO
    public AccountDto.AccountResponse mapAccountToDto(Account account) {
        BigDecimal balance = transactionRepository.calculateBalanceForAccount(account);
        return AccountDto.AccountResponse
                .builder()
                .id(account.getId())
                .name(account.getName())
                .currency(account.getCurrency())
                .type(account.getType())
                .actualBalance(balance)
                .build();
    }

}
