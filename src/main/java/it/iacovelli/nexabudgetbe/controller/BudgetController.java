package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.BudgetDto;
import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.BudgetService;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import it.iacovelli.nexabudgetbe.service.UserService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/budgets")
@Tag(name = "Budgets", description = "Gestione dei budget (creazione, consultazione, utilizzo e residuo)")
public class BudgetController {

    private final BudgetService budgetService;
    private final UserService userService;
    private final CategoryService categoryService;

    public BudgetController(BudgetService budgetService, UserService userService, CategoryService categoryService) {
        this.budgetService = budgetService;
        this.userService = userService;
        this.categoryService = categoryService;
    }

    @PostMapping
    @Operation(summary = "Crea budget", description = "Crea un nuovo budget per una categoria e un utente")
    public ResponseEntity<BudgetDto.BudgetResponse> createBudget(
            @Valid @RequestBody BudgetDto.BudgetRequest budgetRequest,
            @AuthenticationPrincipal User currentUser) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        Category category = categoryService.getCategoryById(budgetRequest.getCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria non trovata"));

        Budget budget = Budget.builder()
                .user(user)
                .category(category)
                .budgetLimit(budgetRequest.getLimit())
                .startDate(budgetRequest.getStartDate())
                .endDate(budgetRequest.getEndDate())
                .build();

        Budget createdBudget = budgetService.createBudget(budget);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapBudgetToResponse(createdBudget));
    }

    @GetMapping("/")
    @Operation(summary = "Budgets utente", description = "Lista di tutti i budgets di un utente")
    public ResponseEntity<List<BudgetDto.BudgetResponse>> getBudgetsByUserId(@AuthenticationPrincipal User currentUser) {
        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        List<BudgetDto.BudgetResponse> budgets = budgetService.getBudgetsByUser(user).stream()
                .map(this::mapBudgetToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(budgets);
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "Budgets per categoria", description = "Budgets di un utente filtrati per categoria")
    public ResponseEntity<List<BudgetDto.BudgetResponse>> getBudgetsByUserAndCategory(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID categoria") @PathVariable UUID categoryId) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        Category category = categoryService.getCategoryById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria non trovata"));

        List<BudgetDto.BudgetResponse> budgets = budgetService.getBudgetsByUserAndCategory(user, category).stream()
                .map(this::mapBudgetToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(budgets);
    }

    @GetMapping("/active")
    @Operation(summary = "Budgets attivi", description = "Budgets attivi alla data indicata (oggi se omessa)")
    public ResponseEntity<List<BudgetDto.BudgetResponse>> getActiveBudgets(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Data di riferimento (ISO yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        LocalDate targetDate = date != null ? date : LocalDate.now();
        List<BudgetDto.BudgetResponse> budgets = budgetService.getActiveBudgets(user, targetDate).stream()
                .map(this::mapBudgetToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(budgets);
    }

    @GetMapping("/usage")
    @Operation(summary = "Utilizzo budgets", description = "Percentuali e importi spesi per budgets attivi alla data (oggi se omessa)")
    public ResponseEntity<List<BudgetDto.UsageResponse>> getBudgetUsage(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Data di riferimento (ISO yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        LocalDate targetDate = date != null ? date : LocalDate.now();
        Map<Budget, BigDecimal> budgetUsage = budgetService.getBudgetUsage(user, targetDate);

        List<BudgetDto.UsageResponse> response = budgetUsage.entrySet().stream()
                .map(entry -> {
                    Budget budget = entry.getKey();
                    BigDecimal spent = entry.getValue();
                    BigDecimal remaining = budget.getBudgetLimit().subtract(spent);
                    double percentageUsed = spent.multiply(BigDecimal.valueOf(100))
                            .divide(budget.getBudgetLimit(), 2, RoundingMode.HALF_UP)
                            .doubleValue();

                    return BudgetDto.UsageResponse.builder()
                            .budgetId(budget.getId())
                            .categoryName(budget.getCategory().getName())
                            .limit(budget.getBudgetLimit())
                            .spent(spent)
                            .remaining(remaining)
                            .percentageUsed(percentageUsed)
                            .build();
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/remaining")
    @Operation(summary = "Residuo budgets", description = "Residuo dei budgets attivi alla data (oggi se omessa)")
    public ResponseEntity<List<BudgetDto.UsageResponse>> getRemainingBudgets(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Data di riferimento (ISO yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        LocalDate targetDate = date != null ? date : LocalDate.now();
        Map<Budget, BigDecimal> remainingBudgets = budgetService.getRemainingBudgets(user, targetDate);

        List<BudgetDto.UsageResponse> response = remainingBudgets.entrySet().stream()
                .map(entry -> {
                    Budget budget = entry.getKey();
                    BigDecimal remaining = entry.getValue();
                    BigDecimal spent = budget.getBudgetLimit().subtract(remaining);
                    double percentageUsed = spent.multiply(BigDecimal.valueOf(100))
                            .divide(budget.getBudgetLimit(), 2, RoundingMode.HALF_UP)
                            .doubleValue();

                    return BudgetDto.UsageResponse.builder()
                            .budgetId(budget.getId())
                            .categoryName(budget.getCategory().getName())
                            .limit(budget.getBudgetLimit())
                            .spent(spent)
                            .remaining(remaining)
                            .percentageUsed(percentageUsed)
                            .build();
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna budget", description = "Aggiorna i dati di un budget esistente")
    public ResponseEntity<BudgetDto.BudgetResponse> updateBudget(
            @Parameter(description = "ID budget") @PathVariable UUID id,
            @Valid @RequestBody BudgetDto.BudgetRequest budgetRequest) {
        Budget existingBudget = budgetService.getBudgetById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget non trovato"));

        Category category = categoryService.getCategoryById(budgetRequest.getCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria non trovata"));

        existingBudget.setCategory(category);
        existingBudget.setBudgetLimit(budgetRequest.getLimit());
        existingBudget.setStartDate(budgetRequest.getStartDate());
        existingBudget.setEndDate(budgetRequest.getEndDate());

        Budget updatedBudget = budgetService.updateBudget(existingBudget);
        return ResponseEntity.ok(mapBudgetToResponse(updatedBudget));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina budget", description = "Elimina un budget per ID")
    public ResponseEntity<Void> deleteBudget(@Parameter(description = "ID budget") @PathVariable UUID id) {
        budgetService.deleteBudget(id);
        return ResponseEntity.noContent().build();
    }

    private BudgetDto.BudgetResponse mapBudgetToResponse(Budget budget) {
        Category category = budget.getCategory();
        return BudgetDto.BudgetResponse.builder()
                .id(budget.getId())
                .categoryId(category.getId())
                .categoryName(category.getName())
                .categoryType(category.getTransactionType())
                .limit(budget.getBudgetLimit())
                .startDate(budget.getStartDate())
                .endDate(budget.getEndDate())
                .build();
    }
}
