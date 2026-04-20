package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.AiReportRequest;
import it.iacovelli.nexabudgetbe.dto.AiReportStatusResponse;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AiReportService;
import it.iacovelli.nexabudgetbe.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Report Finanziari", description = "Analisi e statistiche finanziarie per l'utente")
public class ReportController {

    private final ReportService reportService;
    private final AiReportService aiReportService;

    public ReportController(ReportService reportService, AiReportService aiReportService) {
        this.reportService = reportService;
        this.aiReportService = aiReportService;
    }

    @PostMapping("/ai-analysis")
    @Operation(summary = "Avvia Generazione Report AI", description = "Riceve il taskId per l'analisi AI asincrona di un periodo per massimo 1 anno. Restituisce UUID del job")
    public ResponseEntity<AiReportStatusResponse> startAiReport(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody AiReportRequest request) {
        UUID jobId = aiReportService.startAiReportJob(currentUser, request.startDate(), request.endDate());
        aiReportService.generateAiReport(jobId, currentUser, request.startDate(), request.endDate());
        return ResponseEntity.accepted().body(new AiReportStatusResponse(jobId, "PENDING", null));
    }

    @GetMapping("/ai-analysis/{jobId}")
    @Operation(summary = "Stato Report AI", description = "Polling per check status dell'analisi asincrona. Se completato ritorna anche il contenuto testuale AI.")
    public ResponseEntity<AiReportStatusResponse> getAiReportStatus(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID del job AI") @PathVariable UUID jobId) {
        return ResponseEntity.ok(aiReportService.getJobStatus(jobId));
    }

    @GetMapping("/ai-analysis/{jobId}/download")
    @Operation(summary = "Scarica Report AI in formato file", description = "Scarica il report AI completato come file markdown.")
    public ResponseEntity<Resource> downloadAiReport(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID del job AI") @PathVariable UUID jobId) {
        AiReportStatusResponse status = aiReportService.getJobStatus(jobId);
        
        if (!"COMPLETED".equals(status.status())) {
            return ResponseEntity.badRequest().build();
        }

        byte[] mdBytes = status.content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(mdBytes);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai_report_" + jobId.toString().substring(0, 8) + ".md\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping("/monthly-trend")
    @Operation(summary = "Trend mensile", description = "Entrate e uscite totali per mese negli ultimi N mesi")
    public ResponseEntity<List<ReportDto.MonthlyTrendItem>> getMonthlyTrend(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Numero di mesi (default 12)") @RequestParam(defaultValue = "12") int months) {
        return ResponseEntity.ok(reportService.getMonthlyTrend(currentUser, months));
    }

    @GetMapping("/category-breakdown")
    @Operation(summary = "Breakdown per categoria", description = "Distribuzione delle transazioni per categoria in un intervallo di date")
    public ResponseEntity<ReportDto.CategoryBreakdownResponse> getCategoryBreakdown(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Tipo transazione (IN/OUT)") @RequestParam TransactionType type,
            @Parameter(description = "Data inizio") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Data fine") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(reportService.getCategoryBreakdown(currentUser, type, startDate, endDate));
    }

    @GetMapping("/month-comparison")
    @Operation(summary = "Confronto mese", description = "Confronta entrate/uscite del mese specificato con il mese precedente")
    public ResponseEntity<ReportDto.MonthComparisonResponse> getMonthComparison(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Anno (es. 2025)") @RequestParam int year,
            @Parameter(description = "Mese 1-12") @RequestParam int month) {
        return ResponseEntity.ok(reportService.getMonthComparison(currentUser, year, month));
    }

    @GetMapping("/monthly-projection")
    @Operation(summary = "Proiezione mensile", description = "Proiezione entrate/uscite a fine mese basata sul ritmo attuale")
    public ResponseEntity<ReportDto.MonthlyProjection> getMonthlyProjection(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(reportService.getMonthlyProjection(currentUser));
    }
}
