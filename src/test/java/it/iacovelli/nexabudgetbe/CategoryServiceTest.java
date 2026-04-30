package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.*;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@org.springframework.transaction.annotation.Transactional
class CategoryServiceTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        transactionRepository.hardDeleteAll();
        accountRepository.hardDeleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .build();
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    void tearDown() {
        transactionRepository.hardDeleteAll();
        accountRepository.hardDeleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testCreateCategory() {
        Category category = Category.builder()
                .name("Alimentari")
                .user(testUser)
                .build();

        Category saved = categoryService.createCategory(category);

        assertNotNull(saved.getId());
        assertEquals("Alimentari", saved.getName());
    }

    @Test
    void testGetCategoryById() {
        Category category = Category.builder()
                .name("Trasporti")
                .user(testUser)
                .build();

        Category saved = categoryService.createCategory(category);

        Optional<Category> found = categoryService.getCategoryById(saved.getId());

        assertTrue(found.isPresent());
        assertEquals("Trasporti", found.get().getName());
    }

    @Test
    void testGetCategoryByIdAndUser() {
        Category category = Category.builder()
                .name("Salute")
                .user(testUser)
                .build();

        Category saved = categoryService.createCategory(category);

        Optional<Category> found = categoryService.getCategoryByIdAndUser(saved.getId(), testUser);

        assertTrue(found.isPresent());
        assertEquals("Salute", found.get().getName());
    }

    @Test
    void testGetDefaultCategories() {
        Category defaultCategory = Category.builder()
                .name("Stipendio")
                .build();

        categoryService.createCategory(defaultCategory);

        List<Category> defaults = categoryService.getDefaultCategories();

        assertFalse(defaults.isEmpty());
        assertTrue(defaults.stream().allMatch(c -> c.getUser() == null));
    }

    @Test
    void testGetCategoriesByUser() {
        Category category1 = Category.builder()
                .name("Categoria 1")
                .user(testUser)
                .build();

        Category category2 = Category.builder()
                .name("Categoria 2")
                .user(testUser)
                .build();

        categoryService.createCategory(category1);
        categoryService.createCategory(category2);

        List<Category> categories = categoryService.getCategoriesByUser(testUser);

        assertEquals(2, categories.size());
    }

    @Test
    void testUpdateCategory() {
        Category category = Category.builder()
                .name("Nome Originale")
                .user(testUser)
                .build();

        Category saved = categoryService.createCategory(category);
        saved.setName("Nome Modificato");

        Category updated = categoryService.updateCategory(saved);

        assertEquals("Nome Modificato", updated.getName());
    }

    @Test
    void testDeleteCategory() {
        Category category = Category.builder()
                .name("Da Eliminare")
                .user(testUser)
                .build();

        Category saved = categoryService.createCategory(category);

        categoryService.deleteCategory(saved.getId());

        Optional<Category> found = categoryService.getCategoryById(saved.getId());
        assertFalse(found.isPresent());
    }

    @Test
    void testDeleteCategoryWithUser() {
        Category category = Category.builder()
                .name("User Category")
                .user(testUser)
                .build();

        Category saved = categoryService.createCategory(category);

        categoryService.deleteCategoryWithUser(saved.getId(), testUser);

        Optional<Category> found = categoryService.getCategoryById(saved.getId());
        assertFalse(found.isPresent());
    }

    @Test
    void testCreateDefaultCategories() {
        categoryService.createDefaultCategories();

        List<Category> defaults = categoryService.getDefaultCategories();

        assertFalse(defaults.isEmpty());
        assertTrue(defaults.stream().anyMatch(c -> c.getName().equals("Alimentari")));
        assertTrue(defaults.stream().anyMatch(c -> c.getName().equals("Stipendio")));
        assertTrue(defaults.stream().anyMatch(c -> c.getName().equals("Rimborsi")));
    }

    @Test
    void testCreateCategory_DuplicateUserCategory_Throws() {
        Category category1 = Category.builder()
                .name("Duplicato")
                .user(testUser)
                .build();
        categoryService.createCategory(category1);

        Category category2 = Category.builder()
                .name("Duplicato")
                .user(testUser)
                .build();

        assertThrows(IllegalStateException.class, () -> categoryService.createCategory(category2));
    }

    @Test
    void testCreateCategory_SameNameDifferentUsers_Allowed() {
        User otherUser = userRepository.save(User.builder()
                .username("other")
                .email("other@example.com")
                .passwordHash("hash")
                .build());

        Category cat1 = Category.builder().name("Spesa").user(testUser).build();
        Category cat2 = Category.builder().name("Spesa").user(otherUser).build();

        assertDoesNotThrow(() -> categoryService.createCategory(cat1));
        assertDoesNotThrow(() -> categoryService.createCategory(cat2));
    }

    @Test
    void testCreateDefaultCategories_NotDuplicated() {
        categoryService.createDefaultCategories();
        int firstCount = categoryService.getDefaultCategories().size();

        categoryService.createDefaultCategories();
        int secondCount = categoryService.getDefaultCategories().size();

        assertEquals(firstCount, secondCount);
    }

    @Test
    void testMergeCategories_MovesTransactionsAndDeletesSource() {
        Category source = categoryService.createCategory(Category.builder()
                .name("Da Unire")
                .user(testUser)
                .build());

        Category target = categoryService.createCategory(Category.builder()
                .name("Destinazione")
                .user(testUser)
                .build());

        Account account = accountRepository.save(Account.builder()
                .name("Conto Test")
                .type(AccountType.CONTO_CORRENTE)
                .currency("EUR")
                .user(testUser)
                .build());

        Transaction t = transactionRepository.save(Transaction.builder()
                .user(testUser)
                .account(account)
                .category(source)
                .amount(new BigDecimal("50.00"))
                .type(TransactionType.OUT)
                .description("Transazione da spostare")
                .date(LocalDate.now())
                .build());

        categoryService.mergeCategories(source.getId(), target.getId(), testUser);

        assertFalse(categoryService.getCategoryById(source.getId()).isPresent());
        assertTrue(categoryService.getCategoryById(target.getId()).isPresent());

        Transaction updated = transactionRepository.findById(t.getId()).orElseThrow();
        assertEquals(target.getId(), updated.getCategory().getId());
    }

    @Test
    void testMergeCategories_AcrossDifferentTransactionFlows_Succeeds() {
        Category expenseCategory = categoryService.createCategory(Category.builder()
                .name("Spesa")
                .user(testUser)
                .build());

        Category incomeCategory = categoryService.createCategory(Category.builder()
                .name("Rimborso Spesa")
                .user(testUser)
                .build());

        assertDoesNotThrow(() ->
                categoryService.mergeCategories(incomeCategory.getId(), expenseCategory.getId(), testUser));
    }

    @Test
    void testMergeCategories_SameId_Throws() {
        Category category = categoryService.createCategory(Category.builder()
                .name("Categoria")
                .user(testUser)
                .build());

        assertThrows(IllegalArgumentException.class, () ->
                categoryService.mergeCategories(category.getId(), category.getId(), testUser));
    }
}
