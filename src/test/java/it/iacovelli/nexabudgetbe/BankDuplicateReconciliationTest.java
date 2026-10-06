package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.TransactionService;
import it.iacovelli.nexabudgetbe.service.bank.BankDuplicateReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Import bancario (dedup) e riallineamento dei duplicati lasciati dai sync precedenti
 * (stesso externalId, pending orfane diventate booked con un id diverso).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class BankDuplicateReconciliationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private BankDuplicateReconciliationService reconciliationService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User user;
    private Account account;
    private Category category;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        transactionRepository.hardDeleteAll();
        accountRepository.hardDeleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(User.builder()
                .username("reconcileuser")
                .email("reconcile@example.com")
                .passwordHash("hashedPassword")
                .build());
        account = accountRepository.save(Account.builder()
                .name("Conto GoCardless")
                .type(AccountType.CONTO_CORRENTE)
                .currency("EUR")
                .provider(BankProvider.GOCARDLESS)
                .user(user)
                .build());
        category = categoryRepository.save(Category.builder().name("Spesa").user(user).build());
    }

    // --- import ---

    @Test
    void import_softDeletedTransaction_isNotReimported() {
        Transaction deleted = save("tx-1", "10.00", TransactionType.OUT, today.minusDays(3));
        transactionRepository.softDeleteById(deleted.getId(), LocalDateTime.now());

        transactionService.importNormalizedTransactions(
                new ArrayList<>(List.of(bank("tx-1", "-10.00", today.minusDays(3)))), user, account, null);

        assertTrue(activeTransactions().isEmpty());
    }

    @Test
    void import_missingExternalId_doesNotClashWithManualTransactions() {
        save(null, "5.00", TransactionType.OUT, today.minusDays(1));
        save(null, "7.00", TransactionType.OUT, today.minusDays(1));

        List<NormalizedBankTransaction> batch = List.of(bank(null, "-1.50", today.minusDays(2)), bank(null, "-1.50", today.minusDays(2)));
        transactionService.importNormalizedTransactions(batch, user, account, null);
        // Secondo sync con le stesse transazioni: gli id hash sono deterministici, nessun duplicato.
        transactionService.importNormalizedTransactions(
                List.of(bank(null, "-1.50", today.minusDays(2)), bank(null, "-1.50", today.minusDays(2))), user, account, null);

        List<Transaction> imported = activeTransactions().stream().filter(t -> t.getExternalId() != null).toList();
        assertEquals(2, imported.size());
    }

    // --- riallineamento ---

    @Test
    void reconcile_sameExternalIdTwice_keepsCategorizedRow() {
        Transaction uncategorized = save("tx-dup", "20.00", TransactionType.OUT, today.minusDays(30));
        Transaction categorized = save("tx-dup", "20.00", TransactionType.OUT, today.minusDays(30));
        categorized.setCategory(category);
        transactionRepository.save(categorized);

        int removed = reconciliationService.reconcile(account, List.of(bank("tx-dup", "-20.00", today.minusDays(30))));

        assertEquals(1, removed);
        List<Transaction> active = activeTransactions();
        assertEquals(1, active.size());
        assertEquals(categorized.getId(), active.get(0).getId());
        assertNotEquals(uncategorized.getId(), active.get(0).getId());
    }

    @Test
    void reconcile_orphanPendingMatchingBooked_isRemovedAndCategoryTransferred() {
        save("booked-old", "99.00", TransactionType.OUT, today.minusDays(80));
        Transaction pending = save("pending-1", "12.30", TransactionType.OUT, today.minusDays(21));
        pending.setCategory(category);
        transactionRepository.save(pending);
        Transaction booked = save("booked-1", "12.30", TransactionType.OUT, today.minusDays(19));

        int removed = reconciliationService.reconcile(account, List.of(
                bank("booked-old", "-99.00", today.minusDays(80)),
                bank("booked-1", "-12.30", today.minusDays(19))));

        assertEquals(1, removed);
        List<Transaction> active = activeTransactions();
        assertEquals(2, active.size());
        assertTrue(active.stream().noneMatch(t -> t.getId().equals(pending.getId())));
        Transaction survivor = transactionRepository.findById(booked.getId()).orElseThrow();
        assertEquals(category.getId(), survivor.getCategory().getId());
    }

    @Test
    void reconcile_keepsRowsThatAreNotSafeDuplicates() {
        save("booked-old", "99.00", TransactionType.OUT, today.minusDays(80));
        // Pending recente: potrebbe essere ancora in attesa, non va toccata.
        save("pending-recent", "8.00", TransactionType.OUT, today.minusDays(3));
        save("booked-recent", "8.00", TransactionType.OUT, today.minusDays(2));
        // Orfana senza booked corrispondente (importo diverso).
        save("pending-nomatch", "15.00", TransactionType.OUT, today.minusDays(30));
        // Import da file con stesso importo/data di una booked.
        Transaction fromFile = save("FITID-1", "40.00", TransactionType.OUT, today.minusDays(40));
        fromFile.setImportHash("hash");
        transactionRepository.save(fromFile);
        // Manuale (senza externalId).
        save(null, "40.00", TransactionType.OUT, today.minusDays(40));
        save("booked-40", "40.00", TransactionType.OUT, today.minusDays(40));

        int removed = reconciliationService.reconcile(account, List.of(
                bank("booked-old", "-99.00", today.minusDays(80)),
                bank("booked-recent", "-8.00", today.minusDays(2)),
                bank("booked-40", "-40.00", today.minusDays(40))));

        assertEquals(0, removed);
        assertEquals(7, activeTransactions().size());
    }

    @Test
    void reconcile_orphanImportedAfterBooked_isKept() {
        save("booked-old", "99.00", TransactionType.OUT, today.minusDays(80));
        save("booked-1", "12.30", TransactionType.OUT, today.minusDays(19));
        // Creata dopo la booked: non può essere la sua pending.
        save("other-1", "12.30", TransactionType.OUT, today.minusDays(21));

        int removed = reconciliationService.reconcile(account, List.of(
                bank("booked-old", "-99.00", today.minusDays(80)),
                bank("booked-1", "-12.30", today.minusDays(19))));

        assertEquals(0, removed);
    }

    private List<Transaction> activeTransactions() {
        return transactionRepository.findByAccount(account);
    }

    private Transaction save(String externalId, String amount, TransactionType type, LocalDate date) {
        return transactionRepository.saveAndFlush(Transaction.builder()
                .user(user)
                .account(account)
                .amount(new BigDecimal(amount))
                .type(type)
                .description("Test " + amount)
                .date(date)
                .externalId(externalId)
                .build());
    }

    private NormalizedBankTransaction bank(String externalId, String signedAmount, LocalDate date) {
        return NormalizedBankTransaction.builder()
                .externalId(externalId)
                .amount(new BigDecimal(signedAmount))
                .currency("EUR")
                .date(date.toString())
                .remittanceInformation("Bank " + signedAmount)
                .build();
    }
}
