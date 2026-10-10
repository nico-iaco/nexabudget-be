package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.InvestmentDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.InvestmentPortfolioService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/investments")
@Tag(name = "Investments", description = "Tracking di ETF, azioni, obbligazioni e fondi")
@SecurityRequirement(name = "bearerAuth")
public class InvestmentController {

    private final InvestmentPortfolioService investmentService;

    public InvestmentController(InvestmentPortfolioService investmentService) {
        this.investmentService = investmentService;
    }

    @GetMapping("/search")
    @Operation(summary = "Cerca strumenti",
            description = "Ricerca per ticker, nome o ISIN. Un ISIN può avere più listing (borse/valute): l'utente sceglie quello da tracciare")
    public ResponseEntity<List<InvestmentDto.SearchResult>> search(@RequestParam("q") String query) {
        return ResponseEntity.ok(investmentService.search(query));
    }

    // ─── Asset ──────────────────────────────────────────────────────────────────

    @PostMapping("/assets")
    @Operation(summary = "Crea asset", description = "Aggiunge uno strumento da tracciare. Se manca la valuta viene ricavata dalla quotazione")
    public ResponseEntity<InvestmentDto.AssetResponse> createAsset(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody InvestmentDto.AssetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(investmentService.createAsset(currentUser, request));
    }

    @GetMapping("/assets")
    @Operation(summary = "Elenco asset")
    public ResponseEntity<List<InvestmentDto.AssetResponse>> getAssets(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(investmentService.getAssets(currentUser));
    }

    @GetMapping("/assets/{id}")
    @Operation(summary = "Dettaglio asset")
    public ResponseEntity<InvestmentDto.AssetResponse> getAsset(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(investmentService.getAsset(currentUser, id));
    }

    @PutMapping("/assets/{id}")
    @Operation(summary = "Modifica asset", description = "La valuta non è modificabile")
    public ResponseEntity<InvestmentDto.AssetResponse> updateAsset(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id,
            @Valid @RequestBody InvestmentDto.AssetUpdateRequest request) {
        return ResponseEntity.ok(investmentService.updateAsset(currentUser, id, request));
    }

    @PutMapping("/assets/{id}/manual-price")
    @Operation(summary = "Imposta prezzo manuale",
            description = "È il prezzo usato se priceSource=MANUAL (tipico dei BTP: obbligazioni in % del nominale); negli altri casi è l'ultimo fallback")
    public ResponseEntity<InvestmentDto.AssetResponse> setManualPrice(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id,
            @Valid @RequestBody InvestmentDto.ManualPriceRequest request) {
        return ResponseEntity.ok(investmentService.setManualPrice(currentUser, id, request.getPrice()));
    }

    @DeleteMapping("/assets/{id}")
    @Operation(summary = "Elimina asset", description = "Elimina anche tutte le sue operazioni")
    public ResponseEntity<Void> deleteAsset(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        investmentService.deleteAsset(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    // ─── Operazioni ─────────────────────────────────────────────────────────────

    @PostMapping("/assets/{id}/operations")
    @Operation(summary = "Registra operazione",
            description = "BUY/SELL (quantità e prezzo, commissioni opzionali) o DIVIDEND/COUPON (importo netto). Una vendita oltre la quantità detenuta risponde 409")
    public ResponseEntity<InvestmentDto.OperationResponse> addOperation(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id,
            @Valid @RequestBody InvestmentDto.OperationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(investmentService.addOperation(currentUser, id, request));
    }

    @GetMapping("/assets/{id}/operations")
    @Operation(summary = "Operazioni di un asset", description = "Dalla più recente")
    public ResponseEntity<List<InvestmentDto.OperationResponse>> getOperations(
            @AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(investmentService.getOperations(currentUser, id));
    }

    @PutMapping("/operations/{id}")
    @Operation(summary = "Modifica operazione", description = "Lo storico viene ricontrollato: se una vendita risulta oltre la quantità detenuta risponde 409")
    public ResponseEntity<InvestmentDto.OperationResponse> updateOperation(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id,
            @Valid @RequestBody InvestmentDto.OperationRequest request) {
        return ResponseEntity.ok(investmentService.updateOperation(currentUser, id, request));
    }

    @DeleteMapping("/operations/{id}")
    @Operation(summary = "Elimina operazione", description = "Risponde 409 se l'eliminazione rende incoerente lo storico (es. un acquisto già venduto)")
    public ResponseEntity<Void> deleteOperation(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        investmentService.deleteOperation(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    // ─── Portafoglio ────────────────────────────────────────────────────────────

    @GetMapping("/portfolio")
    @Operation(summary = "Portafoglio valorizzato",
            description = "Posizioni, valore, P/L e allocazione nella valuta indicata (default: valuta dell'utente). Gli importi sono convertiti al cambio corrente")
    public ResponseEntity<InvestmentDto.PortfolioResponse> getPortfolio(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(investmentService.getPortfolio(currentUser, currency));
    }

    @GetMapping("/portfolio/history")
    @Operation(summary = "Storico del valore", description = "Serie degli snapshot giornalieri (disponibili dal giorno di attivazione della funzione)")
    public ResponseEntity<InvestmentDto.HistoryResponse> getHistory(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "12") int months,
            @RequestParam(required = false) String currency) {
        return ResponseEntity.ok(investmentService.getHistory(currentUser, months, currency));
    }

    @GetMapping("/performance")
    @Operation(summary = "Performance di un periodo", description = "Investito, disinvestito, P/L realizzato, cedole/dividendi e guadagno complessivo")
    public ResponseEntity<InvestmentDto.PerformanceResponse> getPerformance(
            @AuthenticationPrincipal User currentUser,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(investmentService.getPerformance(currentUser, startDate, endDate));
    }
}
