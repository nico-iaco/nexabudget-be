package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.BudgetAlertDto;
import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.BudgetAlertService;
import it.iacovelli.nexabudgetbe.service.BudgetService;
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
@Tag(name = "Alert Budget", description = "Notifiche automatiche quando il budget si avvicina al limite")
public class BudgetAlertController {

    private final BudgetAlertService budgetAlertService;
    private final BudgetService budgetService;

    public BudgetAlertController(BudgetAlertService budgetAlertService, BudgetService budgetService) {
        this.budgetAlertService = budgetAlertService;
        this.budgetService = budgetService;
    }

    @PostMapping
    @Operation(summary = "Crea alert", description = "Crea un alert per un budget quando supera una soglia percentuale")
    public ResponseEntity<BudgetAlertDto.BudgetAlertResponse> createAlert(
            @Valid @RequestBody BudgetAlertDto.BudgetAlertRequest request,
            @AuthenticationPrincipal User currentUser) {

        Budget budget = budgetService.getBudgetByIdAndUser(request.getBudgetId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget non trovato"));

        BudgetAlert alert = BudgetAlert.builder()
                .user(currentUser)
                .budget(budget)
                .thresholdPercentage(request.getThresholdPercentage())
                .active(request.getActive() != null ? request.getActive() : true)
                .build();

        BudgetAlert saved = budgetAlertService.createAlert(alert);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @GetMapping
    @Operation(summary = "Lista alert", description = "Tutti gli alert dell'utente")
    public ResponseEntity<List<BudgetAlertDto.BudgetAlertResponse>> getAlerts(
            @AuthenticationPrincipal User currentUser) {
        List<BudgetAlertDto.BudgetAlertResponse> alerts = budgetAlertService.getAlertsByUser(currentUser)
                .stream().map(this::mapToResponse).toList();
        return ResponseEntity.ok(alerts);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna alert", description = "Aggiorna la soglia o lo stato di un alert")
    public ResponseEntity<BudgetAlertDto.BudgetAlertResponse> updateAlert(
            @Parameter(description = "ID alert") @PathVariable UUID id,
            @Valid @RequestBody BudgetAlertDto.BudgetAlertRequest request,
            @AuthenticationPrincipal User currentUser) {

        BudgetAlert existing = budgetAlertService.getAlertByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert non trovato"));

        Budget budget = budgetService.getBudgetByIdAndUser(request.getBudgetId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget non trovato"));

        existing.setBudget(budget);
        existing.setThresholdPercentage(request.getThresholdPercentage());
        if (request.getActive() != null) existing.setActive(request.getActive());

        BudgetAlert updated = budgetAlertService.updateAlert(existing);
        return ResponseEntity.ok(mapToResponse(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina alert", description = "Elimina un alert budget")
    public ResponseEntity<Void> deleteAlert(
            @Parameter(description = "ID alert") @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser) {
        budgetAlertService.deleteAlert(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    private BudgetAlertDto.BudgetAlertResponse mapToResponse(BudgetAlert alert) {
        return BudgetAlertDto.BudgetAlertResponse.builder()
                .id(alert.getId())
                .budgetId(alert.getBudget().getId())
                .categoryName(alert.getBudget().getCategory().getName())
                .budgetLimit(alert.getBudget().getBudgetLimit())
                .thresholdPercentage(alert.getThresholdPercentage())
                .active(alert.getActive())
                .lastNotifiedAt(alert.getLastNotifiedAt())
                .build();
    }
}
