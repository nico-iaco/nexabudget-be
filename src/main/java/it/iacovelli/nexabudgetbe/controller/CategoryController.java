package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.CategoryDto;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import it.iacovelli.nexabudgetbe.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/categories")
@Tag(name = "Categorie", description = "Gestione categorie di transazioni (default + personalizzate)")
public class CategoryController {

    private final CategoryService categoryService;
    private final UserService userService;

    public CategoryController(CategoryService categoryService, UserService userService) {
        this.categoryService = categoryService;
        this.userService = userService;
    }

    @PostMapping
    @Operation(summary = "Crea categoria", description = "Crea una nuova categoria. Se userId non è specificato la categoria è globale (default)")
    public ResponseEntity<CategoryDto.CategoryResponse> createCategory(
            @Valid @RequestBody CategoryDto.CategoryRequest categoryRequest,
            @AuthenticationPrincipal User currentUser) {

        User user = null;
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utente non autenticato");
        }

        user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        Category category = Category.builder()
                .user(user)
                .name(categoryRequest.getName())
                .transactionType(categoryRequest.getTransactionType())
                .build();

        Category createdCategory = categoryService.createCategory(category);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapCategoryToResponse(createdCategory));
    }

    @GetMapping
    @Operation(summary = "Categorie utente", description = "Categorie personalizzate di un utente")
    public ResponseEntity<List<CategoryDto.CategoryResponse>> getCategoriesByUserId(@AuthenticationPrincipal User currentUser) {
        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        List<CategoryDto.CategoryResponse> categories = categoryService.getCategoriesByUser(user).stream()
                .map(this::mapCategoryToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(categories);
    }

    @GetMapping("/type/{type}")
    @Operation(summary = "Categorie per tipo", description = "Categorie disponibili per un utente filtrate per tipo transazione (INCOME/EXPENSE)")
    public ResponseEntity<List<CategoryDto.CategoryResponse>> getAllAvailableCategoriesForUserAndType(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Tipo transazione") @PathVariable TransactionType type) {
        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        List<CategoryDto.CategoryResponse> categories = categoryService.getAllAvailableCategoriesForUserAndType(user, type).stream()
                .map(this::mapCategoryToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(categories);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna categoria", description = "Aggiorna nome e tipo di una categoria esistente")
    public ResponseEntity<CategoryDto.CategoryResponse> updateCategory(
            @Parameter(description = "ID categoria") @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CategoryDto.CategoryRequest categoryRequest) {
        Category existingCategory = categoryService.getCategoryByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria non trovata"));

        existingCategory.setName(categoryRequest.getName());
        existingCategory.setTransactionType(categoryRequest.getTransactionType());

        Category updatedCategory = categoryService.updateCategory(existingCategory);
        return ResponseEntity.ok(mapCategoryToResponse(updatedCategory));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina categoria", description = "Elimina una categoria per ID")
    public ResponseEntity<Void> deleteCategory(@Parameter(description = "ID categoria") @PathVariable UUID id,
                                               @AuthenticationPrincipal User currentUser) {
        categoryService.deleteCategoryWithUser(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    private CategoryDto.CategoryResponse mapCategoryToResponse(Category category) {
        return CategoryDto.CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .transactionType(category.getTransactionType())
                .isDefault(category.getUser() == null)
                .build();
    }
}
