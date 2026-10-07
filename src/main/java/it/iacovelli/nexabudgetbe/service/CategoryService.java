package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetTemplateRepository;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final BudgetTemplateRepository budgetTemplateRepository;

    public CategoryService(CategoryRepository categoryRepository,
                           TransactionRepository transactionRepository,
                           BudgetRepository budgetRepository,
                           BudgetTemplateRepository budgetTemplateRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.budgetTemplateRepository = budgetTemplateRepository;
    }

    public Category createCategory(Category category) {
        if (category.getUser() != null) {
            boolean exists = categoryRepository.existsByUserAndName(category.getUser(), category.getName());
            if (exists) {
                throw new IllegalStateException("Categoria '" + category.getName() + "' già esistente per questo utente");
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

    /** Categoria modificabile dall'utente: le predefinite (condivise da tutti) sono escluse. */
    public Optional<Category> getOwnedCategoryByIdAndUser(UUID id, User user) {
        return categoryRepository.findOwnedByIdAndUser(id, user);
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

    public List<Category> getAllAvailableCategoriesForUser(User user) {
        return categoryRepository.findByUserOrDefault(user);
    }

    public Category updateCategory(Category category) {
        return categoryRepository.save(category);
    }

    public Category renameCategory(Category category, String newName) {
        if (!newName.equals(category.getName())
                && categoryRepository.existsByUserAndName(category.getUser(), newName)) {
            throw new IllegalStateException("Categoria '" + newName + "' già esistente per questo utente");
        }
        category.setName(newName);
        return categoryRepository.save(category);
    }

    public void deleteCategory(UUID categoryId) {
        categoryRepository.deleteById(categoryId);
    }

    public void deleteCategoryWithUser(UUID id, User user) {
        categoryRepository.findOwnedByIdAndUser(id, user).ifPresent(category -> {
            try {
                categoryRepository.delete(category);
            } catch (DataIntegrityViolationException e) {
                throw new IllegalStateException(
                        "Categoria in uso da transazioni, budget o template: unirla a un'altra categoria invece di eliminarla");
            }
        });
    }

    public void createDefaultCategories() {
        if (categoryRepository.findByUserIsNull().isEmpty()) {
            String[] defaults = {"Alimentari", "Trasporti", "Abitazione", "Bollette", "Salute",
                    "Svago", "Abbigliamento", "Istruzione", "Regali", "Stipendio", "Bonus",
                    "Investimenti", "Rimborsi", "Freelance"};
            for (String name : defaults) {
                Category category = new Category();
                category.setName(name);
                categoryRepository.save(category);
            }
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

        transactionRepository.updateCategoryBulkIncludingDeleted(source.getId(), target.getId(), user.getId());
        budgetRepository.updateCategoryBulk(source, target, user);
        budgetTemplateRepository.updateCategoryBulk(source, target, user);
        categoryRepository.delete(source);
    }

}
