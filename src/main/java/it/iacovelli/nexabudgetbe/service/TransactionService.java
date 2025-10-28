package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

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
                .importo(amount)
                .type(TransactionType.OUT)
                .descrizione("Trasferimento a " + destinationAccount.getName() + ": " + description)
                .data(transferDate)
                .note(notes)
                .transferId(transferId)
                .build();

        Transaction inTransaction = Transaction.builder()
                .user(user)
                .account(destinationAccount)
                .importo(amount)
                .type(TransactionType.IN)
                .descrizione("Trasferimento da " + sourceAccount.getName() + ": " + description)
                .data(transferDate)
                .note(notes)
                .transferId(transferId)
                .build();

        Transaction savedOut = transactionRepository.save(outTransaction);
        Transaction savedIn = transactionRepository.save(inTransaction);

        return List.of(mapTransactionToResponse(savedOut), mapTransactionToResponse(savedIn));
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
        return transactionRepository.findByUserAndDataBetween(user, start, end).stream()
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

        oldTransaction.setAccount(newAccount);
        oldTransaction.setImporto(newAmount);
        oldTransaction.setType(newType);
        oldTransaction.setDescrizione(newDescription);
        oldTransaction.setData(newDate);
        oldTransaction.setNote(newNotes);
        oldTransaction.setCategory(newCategory);

        Transaction savedTransaction = transactionRepository.save(oldTransaction);
        return mapTransactionToResponse(savedTransaction);
    }

    @Transactional
    public void deleteTransaction(Transaction transaction) {
        transactionRepository.delete(transaction);
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

    private TransactionDto.TransactionResponse mapTransactionToResponse(Transaction transaction) {
        return TransactionDto.TransactionResponse.builder()
                .id(transaction.getId())
                .accountId(transaction.getAccount().getId())
                .accountName(transaction.getAccount().getName())
                .categoryId(transaction.getCategory() != null ? transaction.getCategory().getId() : null)
                .categoryName(transaction.getCategory() != null ? transaction.getCategory().getName() : null)
                .importo(transaction.getImporto())
                .type(transaction.getType())
                .descrizione(transaction.getDescrizione())
                .data(transaction.getData())
                .note(transaction.getNote())
                .transferId(transaction.getTransferId())
                .build();

    }
}
