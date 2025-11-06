package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public Category createCategory(Category category) {
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
