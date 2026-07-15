package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.BankLinkRequest;
import it.iacovelli.nexabudgetbe.dto.CompleteBankLinkRequest;
import it.iacovelli.nexabudgetbe.dto.SyncBankTransactionsRequest;
import it.iacovelli.nexabudgetbe.dto.bank.BankInstitutionDto;
import it.iacovelli.nexabudgetbe.dto.bank.BankLinkCompletionResult;
import it.iacovelli.nexabudgetbe.dto.bank.BankLinkResult;
import it.iacovelli.nexabudgetbe.dto.bank.CompleteBankLinkSessionRequest;
import it.iacovelli.nexabudgetbe.model.BankProvider;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AccountService;
import it.iacovelli.nexabudgetbe.service.bank.BankAggregationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Endpoint unificato multi-provider per l'aggregazione bancaria (Open Banking/PSD2).
 * Sostituisce progressivamente {@link GocardlessController} (mantenuto come shim deprecato
 * per retrocompatibilità col frontend esistente). Il segmento {provider} nel path accetta
 * "gocardless" o "enable-banking".
 */
@RestController
@RequestMapping("/api/banking")
@Tag(name = "Conti", description = "Gestione multi-provider della sincronizzazione dei conti bancari (GoCardless, Enable Banking)")
@SecurityRequirement(name = "bearerAuth")
public class BankingController {

    private final Logger logger = LoggerFactory.getLogger(BankingController.class);

    private final AccountService accountService;
    private final Map<BankProvider, BankAggregationProvider> providersByEnum;

    public BankingController(AccountService accountService, List<BankAggregationProvider> providers) {
        this.accountService = accountService;
        this.providersByEnum = providers.stream()
                .collect(java.util.stream.Collectors.toMap(BankAggregationProvider::getProvider, Function.identity()));
    }

    private BankProvider parseProvider(String provider) {
        return switch (provider) {
            case "gocardless" -> BankProvider.GOCARDLESS;
            case "enable-banking" -> BankProvider.ENABLE_BANKING;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider bancario sconosciuto: " + provider);
        };
    }

    @GetMapping("/{provider}/banks")
    @Operation(summary = "Lista Banche", description = "Ottieni la lista delle banche/ASPSP supportate dal provider per la nazione richiesta")
    public ResponseEntity<List<BankInstitutionDto>> getBanks(
            @PathVariable String provider,
            @Parameter(description = "Codice nazione delle banche da visualizzare") @RequestParam(required = false) String countryCode) {
        BankProvider bankProvider = parseProvider(provider);
        BankAggregationProvider aggregationProvider = providersByEnum.get(bankProvider);
        logger.debug("Richiesta lista banche per provider: {}", bankProvider);
        return ResponseEntity.ok(aggregationProvider.getInstitutions(countryCode != null ? countryCode : "IT"));
    }

    @PostMapping("/{provider}/link")
    @Operation(summary = "Avvia collegamento bancario", description = "Ottieni il link per collegare un conto bancario tramite il provider richiesto")
    public ResponseEntity<BankLinkResult> getBankLink(
            @PathVariable String provider,
            @RequestBody BankLinkRequest body,
            @AuthenticationPrincipal User currentUser) {
        BankProvider bankProvider = parseProvider(provider);
        logger.debug("Richiesta link bancario provider: {}, institutionId: {}, localAccountId: {}",
                bankProvider, body.getInstitutionId(), body.getLocalAccountId());
        BankLinkResult result = accountService.startBankLink(bankProvider, body.getInstitutionId(), body.getLocalAccountId(), currentUser);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{provider}/{localAccountId}/session")
    @Operation(summary = "Completa collegamento bancario", description = "Scambia il code ricevuto in callback (Enable Banking) con una sessione; no-op per GoCardless")
    public ResponseEntity<BankLinkCompletionResult> completeSession(
            @PathVariable String provider,
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @RequestBody CompleteBankLinkSessionRequest request,
            @AuthenticationPrincipal User currentUser) {
        BankProvider bankProvider = parseProvider(provider);
        BankLinkCompletionResult result = accountService.completeBankLink(bankProvider, localAccountId, request.getCode(), currentUser);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{provider}/{localAccountId}/accounts")
    @Operation(summary = "Ottieni conti bancari", description = "Ottieni i conti disponibili presso il provider per il conto locale collegato")
    public ResponseEntity<BankLinkCompletionResult> getProviderAccounts(
            @PathVariable String provider,
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @AuthenticationPrincipal User currentUser) {
        BankProvider bankProvider = parseProvider(provider);
        return ResponseEntity.ok(accountService.getBankProviderAccounts(bankProvider, localAccountId, currentUser));
    }

    @PostMapping("/{provider}/{localAccountId}/link")
    @Operation(summary = "Collega conto bancario", description = "Collega un conto lato provider ad un conto locale")
    public ResponseEntity<Void> linkBankAccount(
            @PathVariable String provider,
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @RequestBody CompleteBankLinkRequest request,
            @AuthenticationPrincipal User currentUser) {
        BankProvider bankProvider = parseProvider(provider);
        accountService.getAccountByIdAndUser(localAccountId, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));
        accountService.linkAccountToProvider(localAccountId, request.getAccountId(), bankProvider);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{provider}/{localAccountId}/sync")
    @Operation(summary = "Sincronizza transazioni", description = "Avvia la sincronizzazione asincrona delle transazioni bancarie collegate al conto locale")
    public ResponseEntity<String> syncBankTransactions(
            @PathVariable String provider,
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @RequestBody SyncBankTransactionsRequest request,
            @AuthenticationPrincipal User currentUser) {
        // provider non serve qui: è già persistito su Account.provider dal flusso di link.
        parseProvider(provider);
        logger.info("Avvio sincronizzazione asincrona per account ID: {}", localAccountId);
        accountService.syncAccountTransactions(localAccountId, currentUser, request);
        return ResponseEntity.accepted().body("Sincronizzazione avviata in background");
    }
}
