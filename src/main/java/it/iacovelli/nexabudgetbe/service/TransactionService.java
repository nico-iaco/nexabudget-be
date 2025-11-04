package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.GocardlessTransaction;
import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final UserRepository userRepository;

    private final Logger logger = LoggerFactory.getLogger(TransactionService.class);

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public TransactionService(TransactionRepository transactionRepository, UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TransactionDto.TransactionResponse createTransaction(Transaction transaction) {
        Transaction savedTransaction = transactionRepository.save(transaction);
        return mapTransactionToResponse(savedTransaction);
    }

    @Transactional
    public List<TransactionDto.TransactionResponse> createTransfer(Account sourceAccount, Account destinationAccount,
                                                                   BigDecimal amount, String description, LocalDate transferDate, String notes) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("L'importo del trasferimento deve essere positivo");
        }

        String transferId = UUID.randomUUID().toString();
        // Ricarica l'utente per assicurarti che sia gestito dalla sessione corrente
        User user = userRepository.findById(sourceAccount.getUser().getId())
                .orElseThrow(() -> new IllegalStateException("Utente non trovato per il conto di origine"));

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

        Transaction inTransaction = Transaction.builder()
                .user(user)
                .account(destinationAccount)
                .amount(amount)
                .type(TransactionType.IN)
                .description("Trasferimento da " + sourceAccount.getName() + ": " + description)
                .date(transferDate)
                .note(notes)
                .transferId(transferId)
                .build();

        Transaction savedOut = transactionRepository.save(outTransaction);
        Transaction savedIn = transactionRepository.save(inTransaction);

        return List.of(mapTransactionToResponse(savedOut), mapTransactionToResponse(savedIn));
    }

    @Transactional
    public List<TransactionDto.TransactionResponse> convertTransactionsToTransfer(Transaction firstTransaction, Transaction secondTransaction) {
        if (firstTransaction.getTransferId() != null || secondTransaction.getTransferId() != null) {
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

        return List.of(mapTransactionToResponse(savedIn), mapTransactionToResponse(savedOut));
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> getTransactionByIdAndUser(Long id, User user) {
        return transactionRepository.findByIdAndUser(id, user);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByUser(User user) {
        return transactionRepository.findByUser(user).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TransactionDto.TransactionResponse> getTransactionsByAccount(Account account) {
        return transactionRepository.findByAccount(account).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
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
    public List<TransactionDto.TransactionResponse> getTransactionsByAccountAndDateRange(Account account, LocalDateTime start, LocalDateTime end) {
        return transactionRepository.findByAccountAndDateRangeOrderByDateDesc(account, start, end).stream()
                .map(this::mapTransactionToResponse)
                .collect(Collectors.toList());
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
        // Se la transazione fa parte di un trasferimento, elimina anche le altre collegate
        if (transaction.getTransferId() != null) {
            List<Transaction> transferTransactions = transactionRepository.findByTransferIdAndUser(transaction.getTransferId(), transaction.getUser());
            if (!transferTransactions.isEmpty()) {
                transactionRepository.deleteAll(transferTransactions);
            }
        } else {
            // Altrimenti, elimina solo la singola transazione
            transactionRepository.delete(transaction);
        }
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

    public void importTransactionsFromGocardless(List<GocardlessTransaction> transactions, User user, Account account) {
        transactions.forEach(gt -> {
            if (transactionRepository.findByExternalId(gt.getTransactionId()).isEmpty()) {
                BigDecimal rawAmount = new BigDecimal(gt.getTransactionAmount().getAmount());
                Transaction t = new Transaction();
                t.setExternalId(gt.getTransactionId());
                t.setAmount(rawAmount.abs());
                t.setType(rawAmount.signum() > 0 ? TransactionType.IN : TransactionType.OUT);
                t.setUser(user);
                t.setDescription(gt.getPayeeName());
                t.setDate(LocalDate.parse(gt.getValueDate(), formatter));
                t.setAccount(account);
                transactionRepository.save(t);
            } else {
                logger.debug("Gocardless Transaction already exists: {}", gt.getTransactionId());
            }
        });

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
                .build();

    }
}
