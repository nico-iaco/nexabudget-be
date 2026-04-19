package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.GocardlessTransaction;
import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final UserService userService;
    private final AiCategorizationService aiCategorizationService;
    private final ExchangeRateService exchangeRateService;

    private final Logger logger = LoggerFactory.getLogger(TransactionService.class);

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public TransactionService(TransactionRepository transactionRepository, UserService userService,
                              AiCategorizationService aiCategorizationService, ExchangeRateService exchangeRateService) {
        this.transactionRepository = transactionRepository;
        this.userService = userService;
        this.aiCategorizationService = aiCategorizationService;
        this.exchangeRateService = exchangeRateService;
    }

    @Transactional
    public TransactionDto.TransactionResponse createTransaction(Transaction transaction) {
        logger.info("Creazione transazione: {} {} per account ID: {}",
                transaction.getType(), transaction.getAmount(), transaction.getAccount().getId());
        Transaction savedTransaction = transactionRepository.save(transaction);
        logger.debug("Transazione creata con successo: ID: {}", savedTransaction.getId());
        return mapTransactionToResponse(savedTransaction);
    }

    @Transactional
    public List<TransactionDto.TransactionResponse> createTransfer(Account sourceAccount, Account destinationAccount,
                                                                   BigDecimal amount, String description, LocalDate transferDate, String notes) {
        logger.info("Creazione trasferimento: {} da account ID: {} a account ID: {}",
                amount, sourceAccount.getId(), destinationAccount.getId());

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("Tentativo di trasferimento con importo non valido: {}", amount);
            throw new IllegalArgumentException("L'importo del trasferimento deve essere positivo");
        }

        String transferId = UUID.randomUUID().toString();
        // Ricarica l'utente per assicurarti che sia gestito dalla sessione corrente
        User user = userService.getUserById(sourceAccount.getUser().getId())
                .orElseThrow(() -> new IllegalStateException("Utente non trovato per il conto di origine"));

        String sourceCurrency = sourceAccount.getCurrency();
        String destCurrency = destinationAccount.getCurrency();
        boolean multiCurrency = sourceCurrency != null && destCurrency != null
                && !sourceCurrency.equalsIgnoreCase(destCurrency);

        BigDecimal convertedAmount = amount;
        BigDecimal appliedRate = null;
        if (multiCurrency) {
            appliedRate = exchangeRateService.getRate(sourceCurrency, destCurrency)
                    .orElseThrow(() -> new IllegalStateException(
                            "Tasso di cambio non disponibile: " + sourceCurrency + " -> " + destCurrency));
            convertedAmount = amount.multiply(appliedRate).setScale(4, java.math.RoundingMode.HALF_UP);
            logger.info("Trasferimento multi-valuta: {} {} -> {} {} (rate: {})",
                    amount, sourceCurrency, convertedAmount, destCurrency, appliedRate);
        }

        Transaction outTransaction = Transaction.builder()
                .user(user)
                .account(sourceAccount)
                .amount(amount)
                .type(TransactionType.OUT)
                .description("Trasferimento a " + destinationAccount.getName() + ": " + description)
                .date(transferDate)
                .note(notes)
                .transferId(transferId)
                .build();

        Transaction.TransactionBuilder inBuilder = Transaction.builder()
                .user(user)
                .account(destinationAccount)
                .amount(convertedAmount)
                .type(TransactionType.IN)
                .description("Trasferimento da " + sourceAccount.getName() + ": " + description)
                .date(transferDate)
                .note(notes)
                .transferId(transferId);
        if (multiCurrency) {
            inBuilder.exchangeRate(appliedRate)
                    .originalCurrency(sourceCurrency)
                    .originalAmount(amount);
        }
        Transaction inTransaction = inBuilder.build();

        Transaction savedOut = transactionRepository.save(outTransaction);
        Transaction savedIn = transactionRepository.save(inTransaction);

        logger.info("Trasferimento creato con successo: transferId: {}", transferId);
        return List.of(mapTransactionToResponse(savedOut), mapTransactionToResponse(savedIn));
    }

    @Transactional
    public List<TransactionDto.TransactionResponse> convertTransactionsToTransfer(Transaction firstTransaction, Transaction secondTransaction) {
        logger.info("Conversione transazioni a trasferimento: ID1: {}, ID2: {}",
                firstTransaction.getId(), secondTransaction.getId());

        if (firstTransaction.getTransferId() != null || secondTransaction.getTransferId() != null) {
            logger.warn("Tentativo di conversione di transazioni già associate a trasferimento");
            throw new IllegalStateException("Una delle transazioni fa già parte di un trasferimento.");
        }
        if (firstTransaction.getAccount().getId().equals(secondTransaction.getAccount().getId())) {
            throw new IllegalArgumentException("Le transazioni non possono appartenere allo stesso conto.");
        }
        if (firstTransaction.getType() == secondTransaction.getType()) {
            throw new IllegalArgumentException("Le transazioni devono essere di tipo opposto (una IN e una OUT).");
        }

        String transferId = UUID.randomUUID().toString();

        // Determina quale transazione è IN e quale è OUT
        Transaction inTransaction = (firstTransaction.getType() == TransactionType.IN) ? firstTransaction : secondTransaction;
        Transaction outTransaction = (firstTransaction.getType() == TransactionType.OUT) ? firstTransaction : secondTransaction;

        // Normalizza importo, data e note basandosi sulla prima transazione
        BigDecimal amount = firstTransaction.getAmount();
        LocalDate date = firstTransaction.getDate();
        String notes = firstTransaction.getNote();

        inTransaction.setAmount(amount);
        inTransaction.setDate(date);
        inTransaction.setNote(notes);
        inTransaction.setTransferId(transferId);
        inTransaction.setDescription("Trasferimento da " + outTransaction.getAccount().getName() + ": " + inTransaction.getDescription());
        inTransaction.setCategory(null); // I trasferimenti non hanno categoria

        outTransaction.setAmount(amount);
        outTransaction.setDate(date);
        outTransaction.setNote(notes);
        outTransaction.setTransferId(transferId);
        outTransaction.setDescription("Trasferimento a " + inTransaction.getAccount().getName() + ": " + outTransaction.getDescription());
        outTransaction.setCategory(null); // I trasferimenti non hanno categoria

        Transaction savedIn = transactionRepository.save(inTransaction);
        Transaction savedOut = transactionRepository.save(outTransaction);

        logger.info("Transazioni convertite a trasferimento con successo: transferId: {}", transferId);
        return List.of(mapTransactionToResponse(savedIn), mapTransactionToResponse(savedOut));
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> getTransactionByIdAndUser(UUID id, User user) {
        return transactionRepository.findByIdAndUser(id, user);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByUser(User user) {
        return transactionRepository.findByUser(user).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<TransactionDto.TransactionResponse> getTransactionsByUserPaged(User user, Pageable pageable) {
        return transactionRepository.findByUserPaged(user, pageable)
                .map(this::mapTransactionToResponse);
    }

    @Transactional(readOnly = true)
    public Page<TransactionDto.TransactionResponse> getTransactionsFiltered(
            UUID userId, UUID accountId, TransactionType type, UUID categoryId, 
            LocalDate startDate, LocalDate endDate, String search, Pageable pageable) {
        String searchPattern = (search != null && !search.isBlank())
                ? "%" + search.trim().toLowerCase() + "%" : null;
        return transactionRepository.findByFilters(
                userId, accountId, type, categoryId, startDate, endDate, searchPattern, pageable)
                .map(this::mapTransactionToResponse);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByAccount(Account account) {
        return transactionRepository.findByAccount(account).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<TransactionDto.TransactionResponse> getTransactionsByAccountPaged(Account account, Pageable pageable) {
        return transactionRepository.findByAccountPaged(account, pageable)
                .map(this::mapTransactionToResponse);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByCategoryAndUser(Category category, User user) {
        return transactionRepository.findByCategoryAndUser(category, user).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByTransferId(String transferId, User user) {
        return transactionRepository.findByTransferIdAndUser(transferId, user).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByUserAndDateRange(User user, LocalDate start, LocalDate end) {
        return transactionRepository.findByUserAndDateBetween(user, start, end).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByAccountAndDateRange(Account account, LocalDate start, LocalDate end) {
        return transactionRepository.findByAccountAndDateRangeOrderByDateDesc(account, start, end).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<TransactionDto.TransactionResponse> getTransactionsByAccountAndDateRangePaged(Account account, LocalDate start, LocalDate end, Pageable pageable) {
        return transactionRepository.findByAccountAndDateRangePaged(account, start, end, pageable)
                .map(this::mapTransactionToResponse);
    }

    @Transactional
    public TransactionDto.TransactionResponse updateTransaction(Transaction oldTransaction, Account newAccount, Category newCategory,
                                                                BigDecimal newAmount, TransactionType newType, String newDescription,
                                                                LocalDate newDate, String newNotes) {

        // Se la transazione fa parte di un trasferimento, aggiorna anche l'altra
        if (oldTransaction.getTransferId() != null) {
            List<Transaction> transferTransactions = transactionRepository.findByTransferIdAndUser(oldTransaction.getTransferId(), oldTransaction.getUser());
            Transaction otherTransaction = transferTransactions.stream()
                    .filter(t -> !t.getId().equals(oldTransaction.getId()))
                    .findFirst()
                    .orElse(null);

            if (otherTransaction != null) {
                otherTransaction.setAmount(newAmount);
                otherTransaction.setDate(newDate);
                otherTransaction.setNote(newNotes);
                transactionRepository.save(otherTransaction);
            }
        }

        // Aggiorna la cache semantica se la categoria è cambiata
        if (newCategory != null &&
                (oldTransaction.getCategory() == null ||
                        !oldTransaction.getCategory().getId().equals(newCategory.getId()))) {

            logger.info("Aggiornamento cache semantica per transazione: {} con nuova categoria: {}",
                    oldTransaction.getId(), newCategory.getName());

            aiCategorizationService.updateSemanticCache(
                    oldTransaction.getDescription(),
                    oldTransaction.getCategory(),
                    newCategory,
                    oldTransaction.getUser(),
                    newType
            );
        }

        oldTransaction.setAccount(newAccount);
        oldTransaction.setAmount(newAmount);
        oldTransaction.setType(newType);
        oldTransaction.setDescription(newDescription);
        oldTransaction.setDate(newDate);
        oldTransaction.setNote(newNotes);
        oldTransaction.setCategory(newCategory);

        Transaction savedTransaction = transactionRepository.save(oldTransaction);
        return mapTransactionToResponse(savedTransaction);
    }


    @Transactional
    public void deleteTransaction(Transaction transaction) {
        LocalDateTime now = LocalDateTime.now();
        // Se la transazione fa parte di un trasferimento, soft-elimina anche le altre collegate
        if (transaction.getTransferId() != null) {
            List<Transaction> transferTransactions = transactionRepository.findByTransferIdAndUser(transaction.getTransferId(), transaction.getUser());
            for (Transaction t : transferTransactions) {
                transactionRepository.softDeleteById(t.getId(), now);
            }
        } else {
            transactionRepository.softDeleteById(transaction.getId(), now);
        }
    }

    @Transactional
    public void softDeleteAllTransactionByAccount(Account account) {
        transactionRepository.softDeleteAllByAccountId(account.getId(), LocalDateTime.now());
    }

    public void deleteAllTransactionByAccount(Account account) {
        transactionRepository.deleteAllByAccount(account);
    }

    public BigDecimal getIncomeForAccountInPeriod(Account account, LocalDateTime start, LocalDateTime end) {
        BigDecimal sum = transactionRepository.sumByAccountAndTypeAndDateRange(account, TransactionType.IN, start, end);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    public BigDecimal getExpenseForAccountInPeriod(Account account, LocalDateTime start, LocalDateTime end) {
        BigDecimal sum = transactionRepository.sumByAccountAndTypeAndDateRange(account, TransactionType.OUT, start, end);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    public BigDecimal getBalanceForAccountInPeriod(Account account, LocalDateTime start, LocalDateTime end) {
        BigDecimal income = getIncomeForAccountInPeriod(account, start, end);
        BigDecimal expense = getExpenseForAccountInPeriod(account, start, end);
        return income.subtract(expense);
    }

    public void importTransactionsFromGocardless(List<GocardlessTransaction> transactions, User user, Account account, LocalDate startDate) {
        transactions
                //.filter(gc -> startDate == null || LocalDate.parse(gc.getDate()).isAfter(startDate.minusDays(1L)))
                .forEach(gt -> {
                    if (transactionRepository.findByExternalId(gt.getTransactionId()).isEmpty()) {
                        logger.debug("Importing Gocardless Transaction: {}", gt.getTransactionId());
                        BigDecimal rawAmount = new BigDecimal(gt.getTransactionAmount().getAmount());
                        TransactionType txType = rawAmount.signum() > 0 ? TransactionType.IN : TransactionType.OUT;
                        String description = resolveGocardlessDescription(gt, txType);

                        Transaction t = new Transaction();
                        t.setExternalId(gt.getTransactionId());
                        t.setAmount(rawAmount.abs());
                        t.setType(txType);
                        t.setUser(user);
                        t.setDescription(description);
                        t.setDate(LocalDate.parse(gt.getValueDate(), formatter));
                        t.setAccount(account);

                        Optional<Category> foundCategory = aiCategorizationService.categorizeTransaction(description, user, t.getType());

                        if (foundCategory.isPresent()) {
                            t.setCategory(foundCategory.get());
                            logger.debug("Transazione {} categorizzata automaticamente come: {}", gt.getTransactionId(), foundCategory.get().getName());
                        } else {
                            // Se l'AI fallisce o non trova, la categoria resta null (come prima)
                            // L'utente dovrà categorizzarla manualmente
                            logger.debug("Transazione {} non categorizzata automaticamente.", gt.getTransactionId());
                            t.setCategory(null);
                        }

                        transactionRepository.save(t);
                    } else {
                        logger.debug("Gocardless Transaction already exists: {}", gt.getTransactionId());
                    }
                });

    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> findByUserAndDateBetween(User user, LocalDate start, LocalDate end) {
        return transactionRepository.findByUserAndDateBetween(user, start, end).stream().map(t -> mapTransactionToResponse(t)).toList();
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateBalanceForAccount(Account account) {
        return transactionRepository.calculateBalanceForAccount(account);
    }

    /**
     * Builds the best available description from a GoCardless transaction.
     * Priority: creditorName (OUT) / debtorName (IN) → remittanceInformation → payeeName.
     */
    private String resolveGocardlessDescription(GocardlessTransaction gt, TransactionType type) {
        if (type == TransactionType.OUT && gt.getCreditorName() != null && !gt.getCreditorName().isBlank()) {
            return gt.getCreditorName();
        }
        if (type == TransactionType.IN && gt.getDebtorName() != null && !gt.getDebtorName().isBlank()) {
            return gt.getDebtorName();
        }
        List<String> remittance = gt.getRemittanceInformationUnstructuredArray();
        if (remittance != null && !remittance.isEmpty() && remittance.get(0) != null && !remittance.get(0).isBlank()) {
            return remittance.get(0);
        }
        if (gt.getPayeeName() != null && !gt.getPayeeName().isBlank()) {
            return gt.getPayeeName();
        }
        return "";
    }

    private TransactionDto.TransactionResponse mapTransactionToResponse(Transaction transaction) {
        return TransactionDto.TransactionResponse.builder()
                .id(transaction.getId())
                .accountId(transaction.getAccount().getId())
                .accountName(transaction.getAccount().getName())
                .categoryId(transaction.getCategory() != null ? transaction.getCategory().getId() : null)
                .categoryName(transaction.getCategory() != null ? transaction.getCategory().getName() : null)
                .amount(transaction.getAmount())
                .type(transaction.getType())
                .description(transaction.getDescription())
                .date(transaction.getDate())
                .note(transaction.getNote())
                .transferId(transaction.getTransferId())
                .exchangeRate(transaction.getExchangeRate())
                .originalCurrency(transaction.getOriginalCurrency())
                .originalAmount(transaction.getOriginalAmount())
                .build();
    }
}
