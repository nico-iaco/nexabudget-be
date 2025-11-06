package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TransactionServiceTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User testUser;
    private Account testAccount;
    private Category testCategory;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .build();
        testUser = userRepository.save(testUser);

        testAccount = Account.builder()
                .name("Conto Corrente")
                .type(AccountType.CONTO_CORRENTE)
                .currency("EUR")
                .user(testUser)
                .build();
        testAccount = accountRepository.save(testAccount);

        testCategory = Category.builder()
                .name("Alimentari")
                .transactionType(TransactionType.OUT)
                .user(testUser)
                .build();
        testCategory = categoryRepository.save(testCategory);
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testCreateTransaction() {
        Transaction transaction = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .category(testCategory)
                .amount(new BigDecimal("50.00"))
                .type(TransactionType.OUT)
                .description("Spesa al supermercato")
                .date(LocalDate.now())
                .build();

        TransactionDto.TransactionResponse response = transactionService.createTransaction(transaction);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertEquals(new BigDecimal("50.00"), response.getAmount());
        assertEquals("Spesa al supermercato", response.getDescription());
    }

    @Test
    void testCreateTransfer() {
        Account destinationAccount = Account.builder()
                .name("Risparmio")
                .type(AccountType.RISPARMIO)
                .currency("EUR")
                .user(testUser)
                .build();
        destinationAccount = accountRepository.save(destinationAccount);

        List<TransactionDto.TransactionResponse> responses = transactionService.createTransfer(
                testAccount,
                destinationAccount,
                new BigDecimal("100"),
                "Trasferimento mensile",
                LocalDate.now(),
                "Note del trasferimento"
        );

        assertEquals(2, responses.size());

        TransactionDto.TransactionResponse outTransaction = responses.stream()
                .filter(r -> r.getType() == TransactionType.OUT)
                .findFirst()
                .orElseThrow();

        TransactionDto.TransactionResponse inTransaction = responses.stream()
                .filter(r -> r.getType() == TransactionType.IN)
                .findFirst()
                .orElseThrow();

        assertEquals(outTransaction.getTransferId(), inTransaction.getTransferId());
        assertEquals(new BigDecimal("100"), outTransaction.getAmount());
        assertEquals(new BigDecimal("100"), inTransaction.getAmount());
    }

    @Test
    void testCreateTransfer_InvalidAmount() {
        Account destinationAccount = Account.builder()
                .name("Risparmio")
                .type(AccountType.RISPARMIO)
                .currency("EUR")
                .user(testUser)
                .build();
        destinationAccount = accountRepository.save(destinationAccount);

        Account finalDestinationAccount = destinationAccount;
        assertThrows(IllegalArgumentException.class, () ->
                transactionService.createTransfer(
                        testAccount,
                        finalDestinationAccount,
                        BigDecimal.ZERO,
                        "Trasferimento",
                        LocalDate.now(),
                        ""
                )
        );
    }

    @Test
    void testGetTransactionByIdAndUser() {
        Transaction transaction = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("25.50"))
                .type(TransactionType.OUT)
                .description("Test")
                .date(LocalDate.now())
                .build();

        TransactionDto.TransactionResponse created = transactionService.createTransaction(transaction);

        Optional<Transaction> found = transactionService.getTransactionByIdAndUser(created.getId(), testUser);

        assertTrue(found.isPresent());
        assertEquals("Test", found.get().getDescription());
    }

    @Test
    void testGetTransactionsByUser() {
        Transaction transaction1 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("10"))
                .type(TransactionType.OUT)
                .description("Trans 1")
                .date(LocalDate.now())
                .build();

        Transaction transaction2 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("20"))
                .type(TransactionType.IN)
                .description("Trans 2")
                .date(LocalDate.now())
                .build();

        transactionService.createTransaction(transaction1);
        transactionService.createTransaction(transaction2);

        List<TransactionDto.TransactionResponse> transactions = transactionService.getTransactionsByUser(testUser);

        assertEquals(2, transactions.size());
    }

    @Test
    void testUpdateTransaction() {
        Transaction transaction = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("50"))
                .type(TransactionType.OUT)
                .description("Originale")
                .date(LocalDate.now())
                .build();

        TransactionDto.TransactionResponse created = transactionService.createTransaction(transaction);
        Transaction toUpdate = transactionService.getTransactionByIdAndUser(created.getId(), testUser).orElseThrow();

        TransactionDto.TransactionResponse updated = transactionService.updateTransaction(
                toUpdate,
                testAccount,
                testCategory,
                new BigDecimal("75"),
                TransactionType.OUT,
                "Modificata",
                LocalDate.now(),
                "Nuove note"
        );

        assertEquals(new BigDecimal("75"), updated.getAmount());
        assertEquals("Modificata", updated.getDescription());
    }

    @Test
    void testDeleteTransaction() {
        Transaction transaction = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("30"))
                .type(TransactionType.OUT)
                .description("Da eliminare")
                .date(LocalDate.now())
                .build();

        TransactionDto.TransactionResponse created = transactionService.createTransaction(transaction);
        Transaction toDelete = transactionService.getTransactionByIdAndUser(created.getId(), testUser).orElseThrow();

        transactionService.deleteTransaction(toDelete);

        Optional<Transaction> found = transactionService.getTransactionByIdAndUser(created.getId(), testUser);
        assertFalse(found.isPresent());
    }

    @Test
    void testDeleteTransfer() {
        Account destAccount = Account.builder()
                .name("Dest")
                .type(AccountType.RISPARMIO)
                .currency("EUR")
                .user(testUser)
                .build();
        destAccount = accountRepository.save(destAccount);

        List<TransactionDto.TransactionResponse> transfer = transactionService.createTransfer(
                testAccount, destAccount, new BigDecimal("100"),
                "Transfer", LocalDate.now(), ""
        );

        String transferId = transfer.get(0).getTransferId();
        Transaction toDelete = transactionService.getTransactionByIdAndUser(transfer.get(0).getId(), testUser).orElseThrow();

        transactionService.deleteTransaction(toDelete);

        List<TransactionDto.TransactionResponse> remaining = transactionService.getTransactionsByTransferId(transferId, testUser);
        assertTrue(remaining.isEmpty());
    }

    @Test
    void testCalculateBalanceForAccount() {
        Transaction in1 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("100"))
                .type(TransactionType.IN)
                .description("Entrata 1")
                .date(LocalDate.now())
                .build();

        Transaction out1 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("30"))
                .type(TransactionType.OUT)
                .description("Uscita 1")
                .date(LocalDate.now())
                .build();

        transactionService.createTransaction(in1);
        transactionService.createTransaction(out1);

        BigDecimal balance = transactionService.calculateBalanceForAccount(testAccount);

        assertEquals(0, new BigDecimal("70").compareTo(balance));
    }

    @Test
    void testConvertTransactionsToTransfer() {
        Account account2 = Account.builder()
                .name("Account 2")
                .type(AccountType.RISPARMIO)
                .currency("EUR")
                .user(testUser)
                .build();
        account2 = accountRepository.save(account2);

        Transaction outTransaction = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("100"))
                .type(TransactionType.OUT)
                .description("Uscita")
                .date(LocalDate.now())
                .build();

        Transaction inTransaction = Transaction.builder()
                .user(testUser)
                .account(account2)
                .amount(new BigDecimal("100"))
                .type(TransactionType.IN)
                .description("Entrata")
                .date(LocalDate.now())
                .build();

        TransactionDto.TransactionResponse out = transactionService.createTransaction(outTransaction);
        TransactionDto.TransactionResponse in = transactionService.createTransaction(inTransaction);

        Transaction outEntity = transactionService.getTransactionByIdAndUser(out.getId(), testUser).orElseThrow();
        Transaction inEntity = transactionService.getTransactionByIdAndUser(in.getId(), testUser).orElseThrow();

        List<TransactionDto.TransactionResponse> results = transactionService.convertTransactionsToTransfer(
                outEntity, inEntity
        );

        assertEquals(2, results.size());
        assertNotNull(results.get(0).getTransferId());
        assertEquals(results.get(0).getTransferId(), results.get(1).getTransferId());
    }

    @Test
    void testConvertTransactionsToTransfer_SameAccount() {
        Transaction t1 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("100"))
                .type(TransactionType.OUT)
                .description("Trans 1")
                .date(LocalDate.now())
                .build();

        Transaction t2 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("100"))
                .type(TransactionType.IN)
                .description("Trans 2")
                .date(LocalDate.now())
                .build();

        TransactionDto.TransactionResponse r1 = transactionService.createTransaction(t1);
        TransactionDto.TransactionResponse r2 = transactionService.createTransaction(t2);

        Transaction e1 = transactionService.getTransactionByIdAndUser(r1.getId(), testUser).orElseThrow();
        Transaction e2 = transactionService.getTransactionByIdAndUser(r2.getId(), testUser).orElseThrow();

        assertThrows(IllegalArgumentException.class, () ->
                transactionService.convertTransactionsToTransfer(e1, e2)
        );
    }
}
