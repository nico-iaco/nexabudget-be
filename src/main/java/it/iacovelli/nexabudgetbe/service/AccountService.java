package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.dto.GocardlessTransaction;
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
    private final TransactionService transactionService;
    private final GocardlessService gocardlessService;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository, TransactionService transactionService, GocardlessService gocardlessService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.gocardlessService = gocardlessService;
    }

    @Transactional
    public AccountDto.AccountResponse createAccount(Account account, BigDecimal starterBalance) {
        Account savedAccount = accountRepository.save(account);

        // Se il saldo iniziale è diverso da zero, crea la transazione
        if (starterBalance != null && starterBalance.compareTo(BigDecimal.ZERO) != 0) {
            Transaction initialTransaction = Transaction.builder()
                    .user(account.getUser())
                    .account(savedAccount)
                    .amount(starterBalance.abs()) // Usa il valore assoluto
                    .type(starterBalance.compareTo(BigDecimal.ZERO) >= 0 ? TransactionType.IN : TransactionType.OUT)
                    .description("Saldo iniziale")
                    .date(LocalDate.now())
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

    @Transactional
    public void addRequisitionIdToAccount(Long accountId, String requisitionId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        account.setRequisitionId(requisitionId);
        accountRepository.save(account);
    }

    @Transactional
    public void linkAccountToGocardless(Long accountId, String gocardlessAccountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        account.setExternalAccountId(gocardlessAccountId);
        accountRepository.save(account);
    }

    public String getRequisitionIdForAccount(Long accountId, User user) {
        Account account = accountRepository.findByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        return account.getRequisitionId();
    }

    public void syncAccountTransactionWithGocardless(Long accountId, User user) {
        Account account = accountRepository.findByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));

        if (account.getLastExternalSync() != null && account.getLastExternalSync().isAfter(LocalDateTime.now().minusHours(6))) {
            return;
        }

        List<GocardlessTransaction> goCardlessTransaction = gocardlessService.getGoCardlessTransaction(account.getRequisitionId(), account.getExternalAccountId());

        transactionService.importTransactionsFromGocardless(goCardlessTransaction, user, account);
        account.setLastExternalSync(LocalDateTime.now());
        accountRepository.save(account);
    }

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
