package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.dto.SyncBankTransactionsRequest;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingAmount;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingTransaction;
import it.iacovelli.nexabudgetbe.exception.BankReauthRequiredException;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.BankProvider;
import it.iacovelli.nexabudgetbe.model.Transaction;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.AccountService;
import it.iacovelli.nexabudgetbe.service.EnableBankingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifica il dispatch provider-agnostico introdotto in AccountService per l'integrazione
 * Enable Banking: la sincronizzazione asincrona (syncAccountTransactions) deve instradare
 * correttamente su EnableBankingAggregationProvider quando Account.provider = ENABLE_BANKING,
 * importare le transazioni normalizzate con dedup e categorizzazione invariati, e impostare
 * requiresReauth quando il provider segnala un consenso scaduto (BankReauthRequiredException).
 *
 * Non è @Transactional: syncAccountTransactions è @Async e gira su un thread separato (virtual
 * thread), quindi le modifiche vanno committate per essere visibili al thread del test — si fa
 * pulizia manuale in tearDown invece di affidarsi al rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class AccountServiceBankDispatchTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TestConfig.TestDataCleaner testDataCleaner;

    @MockitoBean
    private EnableBankingService enableBankingService;

    private User testUser;

    // Non @Transactional a livello di classe (syncAccountTransactions gira su un thread async
    // separato): la pulizia usa TestDataCleaner, i cui metodi sono @Transactional individualmente,
    // invece di affidarsi a una transazione di test che circondi anche setUp/tearDown.
    @BeforeEach
    void setUp() {
        testDataCleaner.hardDeleteAllTransactions();
        testDataCleaner.hardDeleteAllAccounts();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("bankdispatchuser")
                .email("bankdispatch@example.com")
                .passwordHash("hashedPassword")
                .build();
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.hardDeleteAllTransactions();
        testDataCleaner.hardDeleteAllAccounts();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Account createEnableBankingLinkedAccount() {
        Account account = new Account();
        account.setUser(testUser);
        account.setName("Conto Enable Banking");
        account.setType(AccountType.CONTO_CORRENTE);
        account.setCurrency("EUR");
        account.setProvider(BankProvider.ENABLE_BANKING);
        account.setExternalAccountId("uid-1");
        account.setRequisitionId("session-1");
        account.setIsSynchronizing(false);
        account.setRequiresReauth(false);
        return accountRepository.save(account);
    }

    private EnableBankingTransaction creditTransaction(String entryReference, String amount, String date, String remittance) {
        EnableBankingTransaction t = new EnableBankingTransaction();
        t.setEntryReference(entryReference);
        EnableBankingAmount txAmount = new EnableBankingAmount();
        txAmount.setAmount(amount);
        txAmount.setCurrency("EUR");
        t.setTransactionAmount(txAmount);
        t.setCreditDebitIndicator("CRDT");
        t.setValueDate(date);
        t.setRemittanceInformation(List.of(remittance));
        return t;
    }

    /** Attende il completamento della sincronizzazione asincrona (bounded, senza dipendenze esterne). */
    private Account waitForSyncOutcome(java.util.UUID accountId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Account a = accountRepository.findById(accountId).orElseThrow();
            boolean settled = Boolean.FALSE.equals(a.getIsSynchronizing())
                    && (a.getLastExternalSync() != null || Boolean.TRUE.equals(a.getRequiresReauth()));
            if (settled) {
                return a;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Sincronizzazione asincrona non completata entro il timeout");
    }

    @Test
    void syncAccountTransactions_dispatchesToEnableBankingProvider_andImportsTransactions() throws InterruptedException {
        Account account = createEnableBankingLinkedAccount();

        when(enableBankingService.getTransactions(anyString(), any()))
                .thenReturn(List.of(creditTransaction("tx-1", "50.00", "2026-01-05", "Stipendio")));

        accountService.syncAccountTransactions(account.getId(), testUser, new SyncBankTransactionsRequest());

        Account updated = waitForSyncOutcome(account.getId());
        assertNotNull(updated.getLastExternalSync());
        assertFalse(Boolean.TRUE.equals(updated.getRequiresReauth()));

        List<Transaction> transactions = transactionRepository.findByAccount(updated);
        assertEquals(1, transactions.size());
        Transaction imported = transactions.get(0);
        assertEquals("tx-1", imported.getExternalId());
        assertEquals(0, new java.math.BigDecimal("50.00").compareTo(imported.getAmount()));
        assertEquals(TransactionType.IN, imported.getType());
        assertEquals("Stipendio", imported.getDescription());
    }

    @Test
    void syncAccountTransactions_dedupsOnReSync_viaExternalIdScopedToAccount() throws InterruptedException {
        Account account = createEnableBankingLinkedAccount();

        when(enableBankingService.getTransactions(anyString(), any()))
                .thenReturn(List.of(creditTransaction("tx-dup", "20.00", "2026-01-06", "Rimborso")));

        accountService.syncAccountTransactions(account.getId(), testUser, new SyncBankTransactionsRequest());
        waitForSyncOutcome(account.getId());

        // Forza un secondo giro di sync resettando la guardia delle 6h e il lock.
        Account afterFirst = accountRepository.findById(account.getId()).orElseThrow();
        afterFirst.setLastExternalSync(java.time.LocalDateTime.now().minusHours(7));
        accountRepository.save(afterFirst);

        accountService.syncAccountTransactions(account.getId(), testUser, new SyncBankTransactionsRequest());
        waitForSyncOutcome(account.getId());

        List<Transaction> transactions = transactionRepository.findByAccount(accountRepository.findById(account.getId()).orElseThrow());
        assertEquals(1, transactions.size(), "la stessa externalId sullo stesso conto non deve essere reimportata");
    }

    @Test
    void syncAccountTransactions_bankReauthRequired_setsRequiresReauthFlag() throws InterruptedException {
        Account account = createEnableBankingLinkedAccount();

        when(enableBankingService.getTransactions(anyString(), any()))
                .thenThrow(new BankReauthRequiredException("Sessione scaduta", "401", "SESSION_EXPIRED", true));

        accountService.syncAccountTransactions(account.getId(), testUser, new SyncBankTransactionsRequest());

        Account updated = waitForSyncOutcome(account.getId());
        assertTrue(updated.getRequiresReauth());
        assertNull(updated.getLastExternalSync());
        assertFalse(updated.getIsSynchronizing());
    }
}
