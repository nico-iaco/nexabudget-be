package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Report Finanziari", description = "Analisi e statistiche finanziarie per l'utente")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
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
