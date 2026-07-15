package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.dto.SyncBankTransactionsRequest;
import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import it.iacovelli.nexabudgetbe.exception.BankReauthRequiredException;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.service.bank.BankAggregationProvider;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class AccountService {

    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final TransactionService transactionService;
    private final UserService userService;
    private final CurrencyConversionService currencyConversionService;
    private final Map<BankProvider, BankAggregationProvider> bankProviders;

    public AccountService(AccountRepository accountRepository,
                          TransactionService transactionService,
                          UserService userService,
                          CurrencyConversionService currencyConversionService,
                          List<BankAggregationProvider> bankAggregationProviders) {
        this.accountRepository = accountRepository;
        this.transactionService = transactionService;
        this.userService = userService;
        this.currencyConversionService = currencyConversionService;
        this.bankProviders = bankAggregationProviders.stream()
                .collect(java.util.stream.Collectors.toMap(BankAggregationProvider::getProvider, Function.identity()));
    }

    /**
     * Risolve la strategy per il provider collegato al conto. Un conto senza provider (mai collegato,
     * o legacy prima della colonna `provider`) non può essere sincronizzato.
     */
    private BankAggregationProvider resolveProvider(Account account) {
        BankProvider provider = account.getProvider();
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il conto non è collegato a nessun provider bancario");
        }
        BankAggregationProvider aggregationProvider = bankProviders.get(provider);
        if (aggregationProvider == null) {
            throw new IllegalStateException("Nessun provider registrato per: " + provider);
        }
        return aggregationProvider;
    }

    private BankAggregationProvider resolveProvider(BankProvider provider) {
        BankAggregationProvider aggregationProvider = bankProviders.get(provider);
        if (aggregationProvider == null) {
            throw new IllegalStateException("Nessun provider registrato per: " + provider);
        }
        return aggregationProvider;
    }

    /**
     * Avvia il collegamento di un conto locale a un provider bancario: verifica l'ownership,
     * delega alla strategy corrispondente e persiste il {@code providerReference} già disponibile
     * (requisitionId per GoCardless; per Enable Banking arriva solo dopo {@link #completeBankLink}).
     */
    @Transactional
    public it.iacovelli.nexabudgetbe.dto.bank.BankLinkResult startBankLink(BankProvider provider, String institutionId, UUID localAccountId, User user) {
        accountRepository.findByIdAndUser(localAccountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        it.iacovelli.nexabudgetbe.dto.bank.BankLinkResult result = resolveProvider(provider).startLink(institutionId, localAccountId);
        addRequisitionIdToAccount(localAccountId, result.getProviderReference(), provider);
        return result;
    }

    /**
     * Completa un collegamento avviato da {@link #startBankLink} (scambio del code Enable Banking,
     * o poll GoCardless) e persiste il nuovo {@code providerReference}.
     */
    @Transactional
    public it.iacovelli.nexabudgetbe.dto.bank.BankLinkCompletionResult completeBankLink(BankProvider provider, UUID localAccountId, String code, User user) {
        Account account = accountRepository.findByIdAndUser(localAccountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        it.iacovelli.nexabudgetbe.dto.bank.BankLinkCompletionResult result =
                resolveProvider(provider).completeLink(localAccountId, account.getRequisitionId(), code);
        if (result.getProviderReference() != null) {
            addRequisitionIdToAccount(localAccountId, result.getProviderReference(), provider);
        }
        return result;
    }

    /** Conti disponibili presso il provider per il conto locale già collegato. */
    public it.iacovelli.nexabudgetbe.dto.bank.BankLinkCompletionResult getBankProviderAccounts(BankProvider provider, UUID localAccountId, User user) {
        Account account = accountRepository.findByIdAndUser(localAccountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));
        return resolveProvider(provider).getProviderAccounts(account);
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
        logger.info("Soft-eliminazione account ID: {}", accountId);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));

        transactionService.softDeleteAllTransactionByAccount(account);
        accountRepository.softDeleteById(accountId, java.time.LocalDateTime.now());

        logger.info("Account soft-eliminato: {} (ID: {})", account.getName(), accountId);
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

    public BigDecimal getTotalConvertedBalance(User user) {
        List<Account> accounts = accountRepository.findByUser(user);
        BigDecimal totalBalance = BigDecimal.ZERO;
        String defaultCurrency = user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "EUR";

        for (Account account : accounts) {
            BigDecimal balance = transactionService.calculateBalanceForAccount(account);
            if (balance.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal convertedBalance = currencyConversionService.convert(balance, account.getCurrency(), defaultCurrency);
                totalBalance = totalBalance.add(convertedBalance);
            }
        }

        return totalBalance;
    }

    @Transactional
    public void addRequisitionIdToAccount(UUID accountId, String requisitionId) {
        addRequisitionIdToAccount(accountId, requisitionId, BankProvider.GOCARDLESS);
    }

    @Transactional
    public void addRequisitionIdToAccount(UUID accountId, String requisitionId, BankProvider provider) {
        logger.info("Aggiunta requisitionId {} all'account ID: {} (provider: {})", requisitionId, accountId, provider);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        account.setRequisitionId(requisitionId);
        account.setProvider(provider);
        accountRepository.save(account);
    }

    @Transactional
    public void linkAccountToGocardless(UUID accountId, String gocardlessAccountId) {
        linkAccountToProvider(accountId, gocardlessAccountId, BankProvider.GOCARDLESS);
    }

    @Transactional
    public void linkAccountToProvider(UUID accountId, String providerAccountId, BankProvider provider) {
        logger.info("Collegamento account ID: {} a provider {} accountId: {}", accountId, provider, providerAccountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        account.setExternalAccountId(providerAccountId);
        account.setProvider(provider);
        accountRepository.save(account);
    }

    public String getRequisitionIdForAccount(UUID accountId, User user) {
        Account account = accountRepository.findByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));
        return account.getRequisitionId();
    }

    @Transactional
    public boolean tryAcquireSyncLock(UUID accountId) {
        return accountRepository.markSynchronizing(accountId) > 0;
    }

    /** @deprecated usa {@link #syncAccountTransactions(UUID, User, SyncBankTransactionsRequest)}, provider-agnostico. */
    @Deprecated
    @Async
    public void syncAccountTransactionWithGocardless(UUID accountId, User user, SyncBankTransactionsRequest request) {
        syncAccountTransactions(accountId, user, request);
    }

    @Async
    public void syncAccountTransactions(UUID accountId, User user, SyncBankTransactionsRequest request) {
        logger.info("Sincronizzazione asincrona transazioni bancarie per account ID: {}", accountId);

        Account account = accountRepository.findByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));

        LocalDateTime lastExternalSync = account.getLastExternalSync();

        if (lastExternalSync != null && lastExternalSync.isAfter(LocalDateTime.now().minusHours(6))) {
            logger.info("Account ID: {} già sincronizzato di recente, skip sincronizzazione", accountId);
            return;
        }

        if (!tryAcquireSyncLock(accountId)) {
            logger.info("C'è già una sincronizzazione in corso per account ID: {}, ignorata", accountId);
            return;
        }

        account = accountRepository.findByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato con ID: " + accountId));

        LocalDate startDate = lastExternalSync != null ? lastExternalSync.toLocalDate() : null;

        try {
            BankAggregationProvider provider = resolveProvider(account);
            List<NormalizedBankTransaction> bankTransactions = provider.fetchTransactions(account, startDate);
            logger.info("Recuperate {} transazioni da {} per account ID: {}", bankTransactions.size(), provider.getProvider(), accountId);

            transactionService.importNormalizedTransactions(bankTransactions, user, account, startDate);

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
            account.setRequiresReauth(false);
            logger.info("Sincronizzazione completata per account ID: {}", accountId);
        } catch (BankReauthRequiredException e) {
            account.setRequiresReauth(true);
            logger.warn("Consenso scaduto per account ID: {} — errorCode: {}, providerStatus: {}, renewable: {}. Serve un nuovo collegamento.",
                    accountId, e.getErrorCode(), e.getProviderStatus(), e.isRenewable());
        } catch (Exception e) {
            logger.error("Errore durante la sincronizzazione delle transazioni bancarie per account ID: {}, motivo: {}", accountId, e.getMessage());
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
                .provider(account.getProvider())
                .isLinkedToExternal(account.getExternalAccountId() != null)
                .isSynchronizing(account.getIsSynchronizing() != null && account.getIsSynchronizing())
                .requiresReauth(account.getRequiresReauth() != null && account.getRequiresReauth())
                .build();
    }

}
