package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.BudgetTemplateDto;
import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.BudgetTemplateService;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/budget-templates")
@Tag(name = "Template Budget", description = "Template per la creazione automatica di budget ricorrenti")
public class BudgetTemplateController {

    private final BudgetTemplateService budgetTemplateService;
    private final CategoryService categoryService;

    public BudgetTemplateController(BudgetTemplateService budgetTemplateService, CategoryService categoryService) {
        this.budgetTemplateService = budgetTemplateService;
        this.categoryService = categoryService;
    }

    @PostMapping
    @Operation(summary = "Crea template", description = "Crea un nuovo template di budget ricorrente")
    public ResponseEntity<BudgetTemplateDto.BudgetTemplateResponse> createTemplate(
            @Valid @RequestBody BudgetTemplateDto.BudgetTemplateRequest request,
            @AuthenticationPrincipal User currentUser) {

        Category category = categoryService.getCategoryByIdAndUser(request.getCategoryId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria non trovata"));

        BudgetTemplate template = BudgetTemplate.builder()
                .user(currentUser)
                .category(category)
                .budgetLimit(request.getBudgetLimit())
                .recurrenceType(request.getRecurrenceType())
                .active(request.getActive() != null ? request.getActive() : true)
                .build();

        BudgetTemplate saved = budgetTemplateService.createTemplate(template);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @GetMapping
    @Operation(summary = "Lista template", description = "Tutti i template dell'utente")
    public ResponseEntity<List<BudgetTemplateDto.BudgetTemplateResponse>> getTemplates(
            @AuthenticationPrincipal User currentUser) {
        List<BudgetTemplateDto.BudgetTemplateResponse> templates = budgetTemplateService.getTemplatesByUser(currentUser)
                .stream().map(this::mapToResponse).toList();
        return ResponseEntity.ok(templates);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna template", description = "Aggiorna un template esistente")
    public ResponseEntity<BudgetTemplateDto.BudgetTemplateResponse> updateTemplate(
            @Parameter(description = "ID template") @PathVariable UUID id,
            @Valid @RequestBody BudgetTemplateDto.BudgetTemplateRequest request,
            @AuthenticationPrincipal User currentUser) {

        BudgetTemplate existing = budgetTemplateService.getTemplateByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template non trovato"));

        Category category = categoryService.getCategoryByIdAndUser(request.getCategoryId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria non trovata"));

        existing.setCategory(category);
        existing.setBudgetLimit(request.getBudgetLimit());
        existing.setRecurrenceType(request.getRecurrenceType());
        if (request.getActive() != null) existing.setActive(request.getActive());

        BudgetTemplate updated = budgetTemplateService.updateTemplate(existing);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina template", description = "Elimina un template di budget")
    public ResponseEntity<Void> deleteTemplate(
            @Parameter(description = "ID template") @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser) {
        budgetTemplateService.deleteTemplate(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    private BudgetTemplateDto.BudgetTemplateResponse mapToResponse(BudgetTemplate t) {
        return BudgetTemplateDto.BudgetTemplateResponse.builder()
                .id(t.getId())
                .categoryId(t.getCategory().getId())
                .categoryName(t.getCategory().getName())
                .categoryType(t.getCategory().getTransactionType())
                .budgetLimit(t.getBudgetLimit())
                .recurrenceType(t.getRecurrenceType())
                .active(t.getActive())
                .build();
    }
}
