package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.*;
import it.iacovelli.nexabudgetbe.service.BudgetService;
import it.iacovelli.nexabudgetbe.service.ExchangeRateService;
import it.iacovelli.nexabudgetbe.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@org.springframework.transaction.annotation.Transactional
class BudgetServiceTest {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    private User testUser;
    private User otherUser;
    private Category expenseCategory;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        transactionRepository.hardDeleteAll();
        budgetRepository.deleteAll();
        accountRepository.hardDeleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .username("budgetuser")
                .email("budget@example.com")
                .passwordHash("hashedPassword")
                .build());

        otherUser = userRepository.save(User.builder()
                .username("otheruser")
                .email("other@example.com")
                .passwordHash("hashedPassword")
                .build());

        expenseCategory = categoryRepository.save(Category.builder()
                .name("Alimentari")
                .user(testUser)
                .build());

        testAccount = accountRepository.save(Account.builder()
                .name("Conto Test")
                .type(AccountType.CONTO_CORRENTE)
                .currency("EUR")
                .user(testUser)
                .isSynchronizing(false)
                .build());
    }

    @AfterEach
    void tearDown() {
        transactionRepository.hardDeleteAll();
        budgetRepository.deleteAll();
        accountRepository.hardDeleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ─── CRUD ───────────────────────────────────────────────────────────────────

    @Test
    void testCreateBudget() {
        Budget budget = Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("500.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .endDate(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()))
                .build();

        Budget saved = budgetService.createBudget(budget);

        assertNotNull(saved.getId());
        assertEquals(new BigDecimal("500.00"), saved.getBudgetLimit());
        assertEquals("Alimentari", saved.getCategory().getName());
    }

    @Test
    void testGetBudgetById_Found() {
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("200.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build());

        Optional<Budget> found = budgetService.getBudgetById(budget.getId());

        assertTrue(found.isPresent());
        assertEquals(budget.getId(), found.get().getId());
    }

    @Test
    void testGetBudgetById_NotFound() {
        Optional<Budget> found = budgetService.getBudgetById(UUID.randomUUID());
        assertFalse(found.isPresent());
    }

    @Test
    void testGetBudgetsByUser() {
        budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("300.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build());

        budgetRepository.save(Budget.builder()
                .user(otherUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("100.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build());

        List<Budget> budgets = budgetService.getBudgetsByUser(testUser);

        assertEquals(1, budgets.size());
        assertEquals(testUser.getId(), budgets.get(0).getUser().getId());
    }

    @Test
    void testUpdateBudget() {
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("100.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build());

        budget.setBudgetLimit(new BigDecimal("999.00"));
        Budget updated = budgetService.updateBudget(budget);

        assertEquals(0, new BigDecimal("999.00").compareTo(updated.getBudgetLimit()));
    }

    @Test
    void testDeleteBudget() {
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("100.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build());

        budgetService.deleteBudget(budget.getId());

        assertFalse(budgetRepository.findById(budget.getId()).isPresent());
    }

    // ─── DATE VALIDATION ────────────────────────────────────────────────────────

    @Test
    void testCreateBudget_EndDateBeforeStartDate_Throws() {
        Budget invalidBudget = Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("100.00"))
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().minusDays(1))
                .build();

        assertThrows(IllegalArgumentException.class, () -> budgetService.createBudget(invalidBudget));
    }

    @Test
    void testUpdateBudget_EndDateBeforeStartDate_Throws() {
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("100.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .endDate(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()))
                .build());

        budget.setEndDate(LocalDate.now().withDayOfMonth(1).minusDays(1));

        assertThrows(IllegalArgumentException.class, () -> budgetService.updateBudget(budget));
    }

    @Test
    void testCreateBudget_NullEndDate_IsValid() {
        Budget budget = Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("100.00"))
                .startDate(LocalDate.now())
                .endDate(null)
                .build();

        Budget saved = budgetService.createBudget(budget);
        assertNotNull(saved.getId());
    }

    // ─── OWNERSHIP ──────────────────────────────────────────────────────────────

    @Test
    void testGetBudgetByIdAndUser_Owner() {
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("400.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build());

        Optional<Budget> found = budgetService.getBudgetByIdAndUser(budget.getId(), testUser);

        assertTrue(found.isPresent());
        assertEquals(budget.getId(), found.get().getId());
    }

    @Test
    void testGetBudgetByIdAndUser_WrongUser_ReturnsEmpty() {
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("400.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build());

        Optional<Budget> found = budgetService.getBudgetByIdAndUser(budget.getId(), otherUser);

        assertFalse(found.isPresent());
    }

    // ─── BUDGET ATTIVI ──────────────────────────────────────────────────────────

    @Test
    void testGetActiveBudgets_WithinRange() {
        LocalDate today = LocalDate.now();
        budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("500.00"))
                .startDate(today.withDayOfMonth(1))
                .endDate(today.withDayOfMonth(today.lengthOfMonth()))
                .build());

        List<Budget> active = budgetService.getActiveBudgets(testUser, today);

        assertEquals(1, active.size());
    }

    @Test
    void testGetActiveBudgets_ExpiredBudget_NotReturned() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("500.00"))
                .startDate(lastMonth.withDayOfMonth(1))
                .endDate(lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()))
                .build());

        List<Budget> active = budgetService.getActiveBudgets(testUser, LocalDate.now());

        assertEquals(0, active.size());
    }

    @Test
    void testGetActiveBudgets_OpenEndDate_IsActive() {
        budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("500.00"))
                .startDate(LocalDate.now().minusMonths(3))
                .endDate(null) // nessuna data di fine
                .build());

        List<Budget> active = budgetService.getActiveBudgets(testUser, LocalDate.now());

        assertEquals(1, active.size());
    }

    // ─── USAGE ──────────────────────────────────────────────────────────────────

    @Test
    void testGetBudgetUsage_NoTransactions_SpentIsZero() {
        LocalDate today = LocalDate.now();
        budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("300.00"))
                .startDate(today.withDayOfMonth(1))
                .endDate(today.withDayOfMonth(today.lengthOfMonth()))
                .build());

        Map<Budget, BigDecimal> usage = budgetService.getBudgetUsage(testUser, today);

        assertEquals(1, usage.size());
        usage.values().forEach(spent -> assertEquals(0, BigDecimal.ZERO.compareTo(spent)));
    }

    @Test
    void testGetBudgetUsage_WithTransactions_CorrectSum() {
        LocalDate today = LocalDate.now();
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("300.00"))
                .startDate(today.withDayOfMonth(1))
                .endDate(today.withDayOfMonth(today.lengthOfMonth()))
                .build());

        Transaction t1 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("50.00"))
                .type(TransactionType.OUT)
                .description("Spesa supermercato")
                .category(expenseCategory)
                .date(today)
                .build();
        transactionRepository.save(t1);

        Transaction t2 = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("30.00"))
                .type(TransactionType.OUT)
                .description("Frutta")
                .category(expenseCategory)
                .date(today)
                .build();
        transactionRepository.save(t2);

        Map<Budget, BigDecimal> usage = budgetService.getBudgetUsage(testUser, today);

        BigDecimal spent = usage.entrySet().stream()
                .filter(e -> e.getKey().getId().equals(budget.getId()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        assertNotNull(spent);
        assertEquals(0, new BigDecimal("80.00").compareTo(spent));
    }

    @Test
    void testGetBudgetUsage_RefundOnSameCategory_SubtractsFromSpent() {
        LocalDate today = LocalDate.now();
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("300.00"))
                .startDate(today.withDayOfMonth(1))
                .endDate(today.withDayOfMonth(today.lengthOfMonth()))
                .build());

        transactionRepository.save(Transaction.builder()
                .user(testUser).account(testAccount)
                .amount(new BigDecimal("100.00")).type(TransactionType.OUT)
                .description("Spesa").category(expenseCategory).date(today).build());

        transactionRepository.save(Transaction.builder()
                .user(testUser).account(testAccount)
                .amount(new BigDecimal("30.00")).type(TransactionType.IN)
                .description("Rimborso").category(expenseCategory).date(today).build());

        Map<Budget, BigDecimal> usage = budgetService.getBudgetUsage(testUser, today);

        BigDecimal spent = usage.entrySet().stream()
                .filter(e -> e.getKey().getId().equals(budget.getId()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        assertNotNull(spent);
        assertEquals(0, new BigDecimal("70.00").compareTo(spent));
    }

    @Test
    void testGetRemainingBudgets_CorrectCalculation() {
        LocalDate today = LocalDate.now();
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser)
                .category(expenseCategory)
                .budgetLimit(new BigDecimal("200.00"))
                .startDate(today.withDayOfMonth(1))
                .endDate(today.withDayOfMonth(today.lengthOfMonth()))
                .build());

        Transaction t = Transaction.builder()
                .user(testUser)
                .account(testAccount)
                .amount(new BigDecimal("75.00"))
                .type(TransactionType.OUT)
                .description("Cena fuori")
                .category(expenseCategory)
                .date(today)
                .build();
        transactionRepository.save(t);

        Map<Budget, BigDecimal> remaining = budgetService.getRemainingBudgets(testUser, today);

        BigDecimal rem = remaining.entrySet().stream()
                .filter(e -> e.getKey().getId().equals(budget.getId()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        assertNotNull(rem);
        assertEquals(0, new BigDecimal("125.00").compareTo(rem));
    }

    // ─── PERIODO DEL BUDGET (non mese di calendario) ───────────────────────────

    private void saveExpense(String amount, LocalDate date) {
        transactionRepository.save(Transaction.builder()
                .user(testUser).account(testAccount)
                .amount(new BigDecimal(amount)).type(TransactionType.OUT)
                .description("Spesa").category(expenseCategory).date(date).build());
    }

    private BigDecimal valueFor(Map<Budget, BigDecimal> map, Budget budget) {
        return map.entrySet().stream()
                .filter(e -> e.getKey().getId().equals(budget.getId()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    @Test
    void testGetBudgetUsage_QuarterlyBudget_CountsWholePeriodNotOnlyCurrentMonth() {
        LocalDate ref = LocalDate.of(2026, 3, 15);
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser).category(expenseCategory)
                .budgetLimit(new BigDecimal("1200.00"))
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 3, 31))
                .build());

        saveExpense("400.00", LocalDate.of(2026, 1, 10));
        saveExpense("500.00", LocalDate.of(2026, 2, 10));
        saveExpense("100.00", LocalDate.of(2026, 3, 10));
        saveExpense("999.00", LocalDate.of(2025, 12, 31)); // fuori periodo

        assertEquals(0, new BigDecimal("1000.00").compareTo(valueFor(budgetService.getBudgetUsage(testUser, ref), budget)));
        assertEquals(0, new BigDecimal("200.00").compareTo(valueFor(budgetService.getRemainingBudgets(testUser, ref), budget)));
    }

    @Test
    void testGetBudgetUsage_BudgetAcrossMonths_UsesBudgetBoundaries() {
        LocalDate ref = LocalDate.of(2026, 10, 20);
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser).category(expenseCategory)
                .budgetLimit(new BigDecimal("300.00"))
                .startDate(LocalDate.of(2026, 10, 15))
                .endDate(LocalDate.of(2026, 11, 14))
                .build());

        saveExpense("70.00", LocalDate.of(2026, 10, 5));   // stesso mese ma prima dell'inizio del budget
        saveExpense("40.00", LocalDate.of(2026, 10, 16));
        saveExpense("60.00", LocalDate.of(2026, 11, 10));  // mese successivo ma dentro il budget

        assertEquals(0, new BigDecimal("100.00").compareTo(valueFor(budgetService.getBudgetUsage(testUser, ref), budget)));
    }

    @Test
    void testGetBudgetUsage_OpenEndedBudget_ClosesAtReferenceDate() {
        LocalDate ref = LocalDate.of(2026, 5, 20);
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser).category(expenseCategory)
                .budgetLimit(new BigDecimal("500.00"))
                .startDate(LocalDate.of(2026, 4, 1))
                .endDate(null)
                .build());

        saveExpense("30.00", LocalDate.of(2026, 4, 10));
        saveExpense("20.00", LocalDate.of(2026, 5, 20));
        saveExpense("90.00", LocalDate.of(2026, 5, 21)); // dopo la data di riferimento

        assertEquals(0, new BigDecimal("50.00").compareTo(valueFor(budgetService.getBudgetUsage(testUser, ref), budget)));
    }

    @Test
    void testGetBudgetMonthlySummary_QuarterlyBudget_ReportsBudgetPeriod() {
        LocalDate ref = LocalDate.of(2026, 3, 15);
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser).category(expenseCategory)
                .budgetLimit(new BigDecimal("1000.00"))
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 3, 31))
                .build());

        saveExpense("300.00", LocalDate.of(2026, 1, 10));
        saveExpense("200.00", LocalDate.of(2026, 3, 10));

        var row = budgetService.getBudgetMonthlySummary(testUser, ref).stream()
                .filter(r -> r.getBudgetId().equals(budget.getId()))
                .findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("500.00").compareTo(row.getSpent()));
        assertEquals(0, new BigDecimal("500.00").compareTo(row.getRemaining()));
        assertEquals(50.0, row.getPercentageUsed());
        assertEquals(LocalDate.of(2026, 1, 1), row.getPeriodStart());
        assertEquals(LocalDate.of(2026, 3, 31), row.getPeriodEnd());
    }

    @Test
    void testGetBudgetUsage_AccountsInDifferentCurrencies_ConvertedToUserCurrency() {
        LocalDate ref = LocalDate.of(2026, 6, 15);
        Account usdAccount = accountRepository.save(Account.builder()
                .name("Conto USD").type(AccountType.CONTO_CORRENTE).currency("USD").user(testUser).build());
        org.mockito.Mockito.when(exchangeRateService.getRate("USD", "EUR"))
                .thenReturn(Optional.of(new BigDecimal("0.90")));
        Budget budget = budgetRepository.save(Budget.builder()
                .user(testUser).category(expenseCategory)
                .budgetLimit(new BigDecimal("500.00"))
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .build());

        saveExpense("100.00", LocalDate.of(2026, 6, 5));              // EUR
        transactionRepository.save(Transaction.builder()
                .user(testUser).account(usdAccount)
                .amount(new BigDecimal("200.00")).type(TransactionType.OUT)
                .description("Spesa in USD").category(expenseCategory).date(LocalDate.of(2026, 6, 6)).build());

        // 100 EUR + 200 USD × 0.90 = 280 EUR (utente con valuta preferita EUR)
        assertEquals(0, new BigDecimal("280.00").compareTo(valueFor(budgetService.getBudgetUsage(testUser, ref), budget)));
    }
}
