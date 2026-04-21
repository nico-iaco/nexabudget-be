package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.BudgetAlertDto;
import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.BudgetAlertService;
import it.iacovelli.nexabudgetbe.service.BudgetTemplateService;
import it.iacovelli.nexabudgetbe.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/budget-alerts")
@Tag(name = "Alert Budget", description = "Notifiche automatiche quando la spesa di un budget template si avvicina al limite")
public class BudgetAlertController {

    private final BudgetAlertService budgetAlertService;
    private final BudgetTemplateService budgetTemplateService;
    private final UserService userService;

    public BudgetAlertController(BudgetAlertService budgetAlertService,
                                  BudgetTemplateService budgetTemplateService,
                                  UserService userService) {
        this.budgetAlertService = budgetAlertService;
        this.budgetTemplateService = budgetTemplateService;
        this.userService = userService;
    }

    @PostMapping
    @Operation(summary = "Crea alert", description = "Crea un alert per un budget template specifico")
    public ResponseEntity<BudgetAlertDto.BudgetAlertResponse> createAlert(
            @Valid @RequestBody BudgetAlertDto.BudgetAlertRequest request,
            @AuthenticationPrincipal User currentUser) {

        User user = resolveUser(currentUser);
        BudgetTemplate template = budgetTemplateService.getTemplateByIdAndUser(request.getTemplateId(), user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template non trovato"));

        BudgetAlert alert = BudgetAlert.builder()
                .budgetTemplate(template)
                .user(user)
                .thresholdPercentage(request.getThresholdPercentage())
                .active(request.getActive() != null ? request.getActive() : true)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(budgetAlertService.createAlert(alert)));
    }

    @GetMapping
    @Operation(summary = "Lista alert", description = "Tutti gli alert dell'utente")
    public ResponseEntity<List<BudgetAlertDto.BudgetAlertResponse>> getAlerts(
            @AuthenticationPrincipal User currentUser) {

        User user = resolveUser(currentUser);
        return ResponseEntity.ok(budgetAlertService.getAlertsByUser(user)
                .stream().map(this::mapToResponse).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Dettaglio alert")
    public ResponseEntity<BudgetAlertDto.BudgetAlertResponse> getAlert(
            @Parameter(description = "ID alert") @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser) {

        User user = resolveUser(currentUser);
        BudgetAlert alert = budgetAlertService.getAlertByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert non trovato"));
        return ResponseEntity.ok(mapToResponse(alert));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna alert", description = "Aggiorna soglia e stato dell'alert")
    public ResponseEntity<BudgetAlertDto.BudgetAlertResponse> updateAlert(
            @Parameter(description = "ID alert") @PathVariable UUID id,
            @Valid @RequestBody BudgetAlertDto.BudgetAlertRequest request,
            @AuthenticationPrincipal User currentUser) {

        User user = resolveUser(currentUser);
        BudgetAlert existing = budgetAlertService.getAlertByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert non trovato"));

        BudgetTemplate template = budgetTemplateService.getTemplateByIdAndUser(request.getTemplateId(), user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template non trovato"));

        existing.setBudgetTemplate(template);
        existing.setThresholdPercentage(request.getThresholdPercentage());
        if (request.getActive() != null) existing.setActive(request.getActive());

        return ResponseEntity.ok(mapToResponse(budgetAlertService.updateAlert(existing)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina alert")
    public ResponseEntity<Void> deleteAlert(
            @Parameter(description = "ID alert") @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser) {

        budgetAlertService.deleteAlert(id, resolveUser(currentUser));
        return ResponseEntity.noContent().build();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private User resolveUser(User currentUser) {
        return userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));
    }

    private BudgetAlertDto.BudgetAlertResponse mapToResponse(BudgetAlert alert) {
        BudgetTemplate template = alert.getBudgetTemplate();
        return BudgetAlertDto.BudgetAlertResponse.builder()
                .id(alert.getId())
                .templateId(template.getId())
                .categoryId(template.getCategory() != null ? template.getCategory().getId() : null)
                .categoryName(template.getCategory() != null ? template.getCategory().getName() : null)
                .categoryType(template.getCategory() != null ? template.getCategory().getTransactionType() : null)
                .budgetLimit(template.getBudgetLimit())
                .recurrenceType(template.getRecurrenceType())
                .thresholdPercentage(alert.getThresholdPercentage())
                .active(alert.getActive())
                .lastNotifiedAt(alert.getLastNotifiedAt())
                .createdAt(alert.getCreatedAt())
                .build();
    }
}
