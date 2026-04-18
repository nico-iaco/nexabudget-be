package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;

    public CategoryService(CategoryRepository categoryRepository,
                           TransactionRepository transactionRepository,
                           BudgetRepository budgetRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
    }

    public Category createCategory(Category category) {
        if (category.getUser() != null) {
            boolean exists = categoryRepository.existsByUserAndNameAndTransactionType(
                    category.getUser(), category.getName(), category.getTransactionType());
            if (exists) {
                throw new IllegalStateException("Categoria '" + category.getName() +
                        "' di tipo " + category.getTransactionType() + " già esistente per questo utente");
            }
        }
        return categoryRepository.save(category);
    }

    public Optional<Category> getCategoryById(UUID id) {
        return categoryRepository.findById(id);
    }

    public Optional<Category> getCategoryByIdAndUser(UUID id, User user) {
        return categoryRepository.findByIdAndUser(id, user);
    }

    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    public List<Category> getDefaultCategories() {
        return categoryRepository.findByUserIsNull();
    }

    public List<Category> getCategoriesByUser(User user) {
        return categoryRepository.findByUserOrDefault(user);
    }

    public List<Category> getCategoriesByUserAndType(User user, TransactionType type) {
        return categoryRepository.findByUserAndTransactionType(user, type);
    }

    public List<Category> getAllAvailableCategoriesForUserAndType(User user, TransactionType type) {
        return categoryRepository.findByUserOrDefaultAndTransactionType(user, type);
    }

    public Category updateCategory(Category category) {
        return categoryRepository.save(category);
    }

    public void deleteCategory(UUID categoryId) {
        categoryRepository.deleteById(categoryId);
    }

    public void deleteCategoryWithUser(UUID id, User user) {
        categoryRepository.findByIdAndUser(id, user).ifPresent(categoryRepository::delete);
    }

    public void createDefaultCategories() {
        // Creazione di categorie predefinite se non esistono già
        if (categoryRepository.findByUserIsNull().isEmpty()) {
            createExpenseDefaultCategories();
            createIncomeDefaultCategories();
        }
    }

    private void createExpenseDefaultCategories() {
        String[] expenseCategories = {"Alimentari", "Trasporti", "Abitazione", "Bollette", "Salute",
                "Svago", "Abbigliamento", "Istruzione", "Regali"};
        for (String name : expenseCategories) {
            Category category = new Category();
            category.setName(name);
            category.setTransactionType(TransactionType.OUT);
            categoryRepository.save(category);
        }
    }

    @Transactional
    public void mergeCategories(UUID sourceId, UUID targetId, User user) {
        Category source = categoryRepository.findByIdAndUser(sourceId, user)
                .filter(c -> c.getUser() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Categoria source non trovata o non modificabile (non puoi unire categorie predefinite)"));

        if (source.getId().equals(targetId)) {
            throw new IllegalArgumentException("Source e target devono essere categorie diverse");
        }

        Category target = categoryRepository.findByIdAndUser(targetId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Categoria target non trovata"));

        if (!source.getTransactionType().equals(target.getTransactionType())) {
            throw new IllegalArgumentException(
                    "Le categorie devono avere lo stesso tipo di transazione per poter essere unite");
        }

        transactionRepository.updateCategoryBulk(source, target, user);
        budgetRepository.updateCategoryBulk(source, target, user);
        categoryRepository.delete(source);
    }

    private void createIncomeDefaultCategories() {
        String[] incomeCategories = {"Stipendio", "Bonus", "Regali", "Investimenti", "Rimborsi", "Freelance"};
        for (String name : incomeCategories) {
            Category category = new Category();
            category.setName(name);
            category.setTransactionType(TransactionType.IN);
            categoryRepository.save(category);
        }
    }
}
