package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.dto.GocardlessTransaction;
import it.iacovelli.nexabudgetbe.dto.SyncBankTransactionsRequest;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TransactionService transactionService;
    private final GocardlessService gocardlessService;
    private final UserService userService;

    public AccountService(AccountRepository accountRepository,
                          TransactionService transactionService,
                          GocardlessService gocardlessService,
                          UserService userService) {
        this.accountRepository = accountRepository;
        this.transactionService = transactionService;
        this.gocardlessService = gocardlessService;
        this.userService = userService;
    }

    @Transactional
    public AccountDto.AccountResponse createAccount(AccountDto.AccountRequest accountRequest, BigDecimal starterBalance, UUID userId) {
        logger.info("Creazione nuovo account '{}' per utente ID: {}", accountRequest.getName(), userId);

        User user = userService.getUserById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        Account account = new Account();
        account.setName(accountRequest.getName());
        account.setType(accountRequest.getType());
        account.setCurrency(accountRequest.getCurrency());
        account.setUser(user);
        account.setIsSynchronizing(false);

        Account savedAccount = accountRepository.save(account);
        logger.info("Account creato con successo: {} (ID: {})", savedAccount.getName(), savedAccount.getId());

        // Se il saldo iniziale è diverso da zero, crea la transazione
        if (starterBalance != null && starterBalance.compareTo(BigDecimal.ZERO) != 0) {
            logger.debug("Creazione transazione per saldo iniziale: {} per account ID: {}", starterBalance, savedAccount.getId());
            Transaction initialTransaction = Transaction.builder()
                    .user(savedAccount.getUser())
                    .account(savedAccount)
                    .amount(starterBalance.abs()) // Usa il valore assoluto
                    .type(starterBalance.compareTo(BigDecimal.ZERO) >= 0 ? TransactionType.IN : TransactionType.OUT)
                    .description("Saldo iniziale")
                    .date(LocalDate.now())
                    .build();

            // Salva la transazione
            transactionService.createTransaction(initialTransaction);
        }

        return mapAccountToDto(savedAccount);
    }

    public Optional<Account> getAccountById(UUID id) {
        return accountRepository.findById(id);
    }

    public Optional<AccountDto.AccountResponse> getAccountByIdAndUser(UUID id, User user) {
        return accountRepository.findByIdAndUser(id, user)
                .map(this::mapAccountToDto);
    }

    public Optional<Account> getAccountEntityByIdAndUser(UUID id, User user) {
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
    public AccountDto.AccountResponse updateAccount(UUID accountId, User user, String newName, AccountType newType, String newCurrency) {

        Account account = getAccountEntityByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        account.setName(newName);
        account.setType(newType);
        account.setCurrency(newCurrency);
        Account updatedAccount = accountRepository.save(account);
        return mapAccountToDto(updatedAccount);
    }

    @Transactional
    public void deleteAccount(UUID accountId) {
        logger.info("Eliminazione account ID: {}", accountId);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));

        transactionService.deleteAllTransactionByAccount(account);
        accountRepository.delete(account);

        logger.info("Account eliminato con successo: {} (ID: {})", account.getName(), accountId);
    }

    public BigDecimal getTotalBalance(User user, String currency) {
        List<Account> accounts = accountRepository.findByUserAndCurrency(user, currency);
        BigDecimal totalBalance = BigDecimal.ZERO;

        for (Account account : accounts) {
            BigDecimal balance = transactionService.calculateBalanceForAccount(account);
            totalBalance = totalBalance.add(balance);
        }

        return totalBalance;
    }

    @Transactional
    public void addRequisitionIdToAccount(UUID accountId, String requisitionId) {
        logger.info("Aggiunta requisitionId {} all'account ID: {}", requisitionId, accountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        account.setRequisitionId(requisitionId);
        accountRepository.save(account);
    }

    @Transactional
    public void linkAccountToGocardless(UUID accountId, String gocardlessAccountId) {
        logger.info("Collegamento account ID: {} a GoCardless accountId: {}", accountId, gocardlessAccountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        account.setExternalAccountId(gocardlessAccountId);
        accountRepository.save(account);
    }

    public String getRequisitionIdForAccount(UUID accountId, User user) {
        Account account = accountRepository.findByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        return account.getRequisitionId();
    }

    @Async
    public void syncAccountTransactionWithGocardless(UUID accountId, User user, SyncBankTransactionsRequest request) {
        logger.info("Sincronizzazione asincrona transazioni GoCardless per account ID: {}", accountId);

        Account account = accountRepository.findByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));

        LocalDateTime lastExternalSync = account.getLastExternalSync();

        if (account.getIsSynchronizing()) {
            logger.info("C'è già una sincronizzazione in corso per account ID: {}, ignorata", accountId);
            return;
        } else {
            account.setIsSynchronizing(true);
            account = accountRepository.save(account);
        }

        if (lastExternalSync != null && lastExternalSync.isAfter(LocalDateTime.now().minusHours(6))) {
            logger.info("Account ID: {} già sincronizzato di recente, skip sincronizzazione", accountId);
            return;
        }

        LocalDate startDate = lastExternalSync != null ? lastExternalSync.toLocalDate() : null;

        List<GocardlessTransaction> goCardlessTransaction = gocardlessService.getGoCardlessTransaction(account.getRequisitionId(), account.getExternalAccountId());
        logger.info("Recuperate {} transazioni da GoCardless per account ID: {}", goCardlessTransaction.size(), accountId);

        try {
            transactionService.importTransactionsFromGocardless(goCardlessTransaction, user, account, startDate);

            if (request.getActualBalance() != null) {
                // Controlla adesso il bilancio del conto corrente e lo allinea con quello atteso della request
                BigDecimal savedBalance = transactionService.calculateBalanceForAccount(account);
                if (savedBalance.compareTo(request.getActualBalance()) != 0) {
                    logger.info("Allineamento bilancio necessario per account ID: {}, bilancio attuale: {}, atteso: {}",
                            accountId, savedBalance, request.getActualBalance());
                    Transaction alignmentTransaction = Transaction.builder()
                            .account(account)
                            .user(user)
                            .amount(savedBalance.compareTo(request.getActualBalance()) < 0 ? request.getActualBalance().subtract(savedBalance) : savedBalance.subtract(request.getActualBalance()))
                            .type(savedBalance.compareTo(request.getActualBalance()) < 0 ? TransactionType.IN : TransactionType.OUT)
                            .description("Allineamento conto")
                            .date(LocalDate.now())
                            .build();


                    transactionService.createTransaction(alignmentTransaction);
                }
            }

            account.setLastExternalSync(LocalDateTime.now());
            logger.info("Sincronizzazione completata per account ID: {}", accountId);
        } catch (Exception e) {
            logger.error("Errore durante la sincronizzazione delle transazioni GoCardless per account ID: {}, motivo: {}", accountId, e.getMessage());
        } finally {
            account.setIsSynchronizing(false);
            accountRepository.save(account);
        }
    }

    public AccountDto.AccountResponse mapAccountToDto(Account account) {
        BigDecimal balance = transactionService.calculateBalanceForAccount(account);
        return AccountDto.AccountResponse
                .builder()
                .id(account.getId())
                .name(account.getName())
                .currency(account.getCurrency())
                .type(account.getType())
                .actualBalance(balance)
                .isLinkedToExternal(account.getExternalAccountId() != null)
                .isSynchronizing(account.getIsSynchronizing() != null && account.getIsSynchronizing())
                .build();
    }

}
