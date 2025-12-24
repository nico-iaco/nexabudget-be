package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.CryptoDto;
import it.iacovelli.nexabudgetbe.dto.CryptoHoldingDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.CryptoPortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/crypto")
@Tag(name = "Crypto", description = "Gestione portafoglio crypto")
@SecurityRequirement(name = "bearerAuth")
public class CryptoPortfolioController {

    private final CryptoPortfolioService cryptoService;

    public CryptoPortfolioController(CryptoPortfolioService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @GetMapping("/portfolio")
    @Operation(summary = "Valore portafoglio", description = "Ottiene il valore totale e dettagliato del portafoglio crypto nella valuta specificata")
    public ResponseEntity<CryptoDto.PortfolioValueResponse> getPortfolioValue(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "EUR") String currency) {
        return ResponseEntity.ok(cryptoService.getPortfolioValue(currentUser, currency));
    }

    @PostMapping("/holdings")
    @Operation(summary = "Aggiungi/Aggiorna holding manuale", description = "Crea o aggiorna un asset inserito manualmente")
    public ResponseEntity<CryptoHoldingDto> addManualHolding(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CryptoDto.ManualHoldingRequest request) {
        CryptoHoldingDto holding = cryptoService.addManualHolding(
                currentUser, request.getSymbol(), request.getAmount());
        return ResponseEntity.status(HttpStatus.CREATED).body(holding);
    }

    @PatchMapping("/holdings/{id}")
    @Operation(summary = "Modifica holding manuale", description = "Modifica la quantità di un asset manuale esistente")
    public ResponseEntity<CryptoHoldingDto> updateManualHolding(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id,
            @Valid @RequestBody CryptoDto.UpdateHoldingRequest request) {
        CryptoHoldingDto holding = cryptoService.updateManualHolding(
                currentUser, id, request.getAmount());
        return ResponseEntity.ok(holding);
    }

    @DeleteMapping("/holdings/{id}")
    @Operation(summary = "Elimina holding manuale", description = "Elimina un asset manuale")
    public ResponseEntity<Void> deleteManualHolding(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id) {
        cryptoService.deleteManualHolding(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/binance/keys")
    @Operation(summary = "Salva chiavi Binance", description = "Salva (in modo sicuro) le chiavi API di Binance per l'utente")
    public ResponseEntity<Void> saveBinanceKeys(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CryptoDto.BinanceKeysRequest request) {
        cryptoService.saveBinanceKeys(currentUser, request.getApiKey(), request.getApiSecret());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/binance/sync")
    @Operation(summary = "Sincronizza da Binance", description = "Avvia l'importazione/aggiornamento degli asset da Binance")
    public ResponseEntity<Void> syncFromBinance(
            @AuthenticationPrincipal User currentUser) {
        cryptoService.syncBinanceHoldings(currentUser);
        return ResponseEntity.accepted().build(); // È un'operazione che può richiedere tempo
    }
}
