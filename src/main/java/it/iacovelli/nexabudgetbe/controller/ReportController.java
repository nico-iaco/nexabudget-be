package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.AiReportRequest;
import it.iacovelli.nexabudgetbe.dto.AiReportStatusResponse;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AiReportPdfService;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Report Finanziari", description = "Analisi e statistiche finanziarie per l'utente")
public class ReportController {

    private final ReportService reportService;
    private final AiReportService aiReportService;
    private final AiReportPdfService aiReportPdfService;

    public ReportController(ReportService reportService, AiReportService aiReportService, AiReportPdfService aiReportPdfService) {
        this.reportService = reportService;
        this.aiReportService = aiReportService;
        this.aiReportPdfService = aiReportPdfService;
    }

    @PostMapping("/ai-analysis")
    @Operation(summary = "Avvia Generazione Report AI", description = "Riceve il taskId per l'analisi AI asincrona di un periodo per massimo 1 anno. Restituisce UUID del job")
    public ResponseEntity<AiReportStatusResponse> startAiReport(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody AiReportRequest request) {
        UUID jobId = aiReportService.startAiReportJob(currentUser, request.startDate(), request.endDate());
        aiReportService.generateAiReport(jobId, currentUser, request.startDate(), request.endDate());
        return ResponseEntity.accepted().body(new AiReportStatusResponse(jobId, "PENDING", null, request.startDate(), request.endDate()));
    }

    @GetMapping("/ai-analysis/{jobId}")
    @Operation(summary = "Stato Report AI", description = "Polling per check status dell'analisi asincrona. Se completato ritorna anche il contenuto testuale AI.")
    public ResponseEntity<AiReportStatusResponse> getAiReportStatus(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID del job AI") @PathVariable UUID jobId) {
        return ResponseEntity.ok(aiReportService.getJobStatus(jobId, currentUser));
    }

    @GetMapping("/ai-analysis/{jobId}/download")
    @Operation(summary = "Scarica Report AI in formato file", description = "Scarica il report AI completato come PDF.")
    public ResponseEntity<Resource> downloadAiReport(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID del job AI") @PathVariable UUID jobId) {
        AiReportStatusResponse status = aiReportService.getJobStatus(jobId, currentUser);
        
        if (!"COMPLETED".equals(status.status())) {
            return ResponseEntity.badRequest().build();
        }
        if (status.startDate() == null || status.endDate() == null) {
            return ResponseEntity.badRequest().build();
        }
        byte[] pdfBytes = aiReportPdfService.buildReportPdf(currentUser, status.startDate(), status.endDate(), status.content());
        ByteArrayResource pdfResource = new ByteArrayResource(pdfBytes);
        String filename = aiReportPdfService.buildFilename(status.startDate(), status.endDate());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfResource);
    }

    @GetMapping("/monthly-trend")
    @Operation(summary = "Trend mensile", description = "Entrate e uscite totali per mese negli ultimi N mesi, convertiti nella valuta di default dell'utente")
    public ResponseEntity<ReportDto.MonthlyTrendResponse> getMonthlyTrend(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Numero di mesi (default 12)") @RequestParam(defaultValue = "12") int months) {
        return ResponseEntity.ok(reportService.getMonthlyTrend(currentUser, months));
    }

    @GetMapping("/category-breakdown")
    @Operation(summary = "Breakdown per categoria", description = "Distribuzione netta (OUT-IN) delle transazioni per categoria in un intervallo di date")
    public ResponseEntity<ReportDto.CategoryBreakdownResponse> getCategoryBreakdown(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Data inizio") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Data fine") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(reportService.getCategoryBreakdown(currentUser, startDate, endDate));
    }

    @GetMapping("/month-comparison")
    @Operation(summary = "Confronto mese", description = "Confronta entrate/uscite del mese specificato con il mese precedente")
    public ResponseEntity<ReportDto.MonthComparisonResponse> getMonthComparison(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Anno (es. 2025)") @RequestParam int year,
            @Parameter(description = "Mese 1-12") @RequestParam int month) {
        return ResponseEntity.ok(reportService.getMonthComparison(currentUser, year, month));
    }

    @GetMapping("/balance-trend")
    @Operation(summary = "Andamento saldo mensile", description = "Saldo netto cumulato (IN-OUT) di chiusura per ciascun mese nel range specificato, partendo dal saldo iniziale calcolato sulle transazioni precedenti a startDate")
    public ResponseEntity<ReportDto.BalanceTrendResponse> getBalanceTrend(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Data inizio (verrà allineata al primo giorno del mese)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Data fine (verrà allineata all'ultimo giorno del mese)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(reportService.getBalanceTrend(currentUser, startDate, endDate));
    }

    @GetMapping("/monthly-projection")
    @Operation(summary = "Proiezione mensile", description = "Proiezione entrate/uscite a fine mese basata sul ritmo attuale")
    public ResponseEntity<ReportDto.MonthlyProjection> getMonthlyProjection(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(reportService.getMonthlyProjection(currentUser));
    }
}
