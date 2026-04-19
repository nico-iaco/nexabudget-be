package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.AuditLogDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit-log")
@Tag(name = "Audit Log", description = "Storico operazioni dell'utente")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @Operation(summary = "Log operazioni utente", description = "Pagina del log di audit per l'utente corrente, ordinato per data decrescente")
    public ResponseEntity<Page<AuditLogDto.AuditLogResponse>> getAuditLog(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AuditLogDto.AuditLogResponse> result = auditLogService.getAuditLogForUser(
                currentUser, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp")));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{entityType}/{entityId}")
    @Operation(summary = "Log per entità", description = "Storico operazioni su una specifica entità (es. Transaction, Account)")
    public ResponseEntity<List<AuditLogDto.AuditLogResponse>> getAuditLogForEntity(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @AuthenticationPrincipal User currentUser) {
        List<AuditLogDto.AuditLogResponse> result = auditLogService.getAuditLogForEntity(entityType, entityId);
        return ResponseEntity.ok(result);
    }
}
