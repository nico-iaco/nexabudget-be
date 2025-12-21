package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import it.iacovelli.nexabudgetbe.config.TestConfig;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class CategoryServiceTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
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
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testCreateCategory() {
        Category category = Category.builder()
                .name("Alimentari")
                .transactionType(TransactionType.OUT)
                .user(testUser)
                .build();

        Category saved = categoryService.createCategory(category);

        assertNotNull(saved.getId());
        assertEquals("Alimentari", saved.getName());
        assertEquals(TransactionType.OUT, saved.getTransactionType());
    }

    @Test
    void testGetCategoryById() {
        Category category = Category.builder()
                .name("Trasporti")
                .transactionType(TransactionType.OUT)
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
                .transactionType(TransactionType.OUT)
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
                .transactionType(TransactionType.IN)
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
                .transactionType(TransactionType.OUT)
                .user(testUser)
                .build();

        Category category2 = Category.builder()
                .name("Categoria 2")
                .transactionType(TransactionType.IN)
                .user(testUser)
                .build();

        categoryService.createCategory(category1);
        categoryService.createCategory(category2);

        List<Category> categories = categoryService.getCategoriesByUser(testUser);

        assertEquals(2, categories.size());
    }

    @Test
    void testGetCategoriesByUserAndType() {
        Category category1 = Category.builder()
                .name("Uscita")
                .transactionType(TransactionType.OUT)
                .user(testUser)
                .build();

        Category category2 = Category.builder()
                .name("Entrata")
                .transactionType(TransactionType.IN)
                .user(testUser)
                .build();

        categoryService.createCategory(category1);
        categoryService.createCategory(category2);

        List<Category> outCategories = categoryService.getCategoriesByUserAndType(testUser, TransactionType.OUT);

        assertEquals(1, outCategories.size());
        assertEquals("Uscita", outCategories.get(0).getName());
    }

    @Test
    void testUpdateCategory() {
        Category category = Category.builder()
                .name("Nome Originale")
                .transactionType(TransactionType.OUT)
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
                .transactionType(TransactionType.OUT)
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
                .transactionType(TransactionType.OUT)
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
    }

    @Test
    void testCreateDefaultCategories_NotDuplicated() {
        categoryService.createDefaultCategories();
        int firstCount = categoryService.getDefaultCategories().size();

        categoryService.createDefaultCategories();
        int secondCount = categoryService.getDefaultCategories().size();

        assertEquals(firstCount, secondCount);
    }
}
