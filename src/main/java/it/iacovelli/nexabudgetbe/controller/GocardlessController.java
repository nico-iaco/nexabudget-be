package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.*;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AccountService;
import it.iacovelli.nexabudgetbe.service.GocardlessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/gocardless")
@Tag(name = "Conti", description = "Gestione dell'integrazione dei conti dell'utente con GoCardless")
@SecurityRequirement(name = "bearerAuth")
public class GocardlessController {

    private final Logger logger = LoggerFactory.getLogger(GocardlessController.class);

    private final GocardlessService gocardlessService;

    private final AccountService accountService;

    public GocardlessController(GocardlessService gocardlessService, AccountService accountService) {
        this.gocardlessService = gocardlessService;
        this.accountService = accountService;
    }

    @GetMapping("/bank")
    @Operation(summary = "Lista Banche", description = "Ottieni la lista delle banche supportate per la nazione richiesta")
    public ResponseEntity<List<GocardlessBank>> getBanks(@Parameter(name = "Codice nazione delle banche da visualizzare") @RequestParam(required = false) String countryCode) {
        logger.debug("Received request to get banks");
        if (countryCode == null) {
            countryCode = "IT";
        }
        List<GocardlessBank> banks = gocardlessService.getBanks(countryCode);
        return ResponseEntity.ok(banks);
    }

    @PostMapping("/bank/link")
    @Operation(summary = "Ottieni link bank", description = "Ottieni il link per collegare un conto bancario tramite GoCardless")
    public ResponseEntity<String> getBankLink(@RequestBody BankLinkRequest body) {
        String institutionId = body.getInstitutionId();
        UUID localAccountId = body.getLocalAccountId();
        logger.debug("Received request to get bank link for institutionId: {} and localAccountId: {}", institutionId, localAccountId);
        GocardlessCreateWebToken bankLink = gocardlessService.generateBankLinkForToken(institutionId, localAccountId);
        accountService.addRequisitionIdToAccount(localAccountId, bankLink.getRequisitionId());
        return ResponseEntity.ok(bankLink.getLink());
    }

    @GetMapping("/bank/{localAccountId}/account")
    @Operation(summary = "Ottieni conti bancari", description = "Ottieni i conti bancari associati alla banca richiesta")
    public ResponseEntity<List<GocardlessBankDetail>> getBankAccounts(
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @AuthenticationPrincipal User currentUser) {
        String requisitionIdForAccount = accountService.getRequisitionIdForAccount(localAccountId, currentUser);
        List<GocardlessBankDetail> bankAccounts = gocardlessService.getBankAccounts(requisitionIdForAccount);
        return ResponseEntity.ok(bankAccounts);
    }

    @PostMapping("/bank/{localAccountId}/link")
    @Operation(summary = "Collega conto bancario", description = "Collega un conto bancario ad un conto locale")
    public ResponseEntity<Void> linkBankAccount(
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @RequestBody CompleteBankLinkRequest request,
            @AuthenticationPrincipal User currentUser) {
        AccountDto.AccountResponse accountResponse = accountService.getAccountByIdAndUser(localAccountId, currentUser)
                .orElseThrow(() -> new IllegalArgumentException("Conto non trovato"));
        accountService.linkAccountToGocardless(accountResponse.getId(), request.getAccountId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bank/{localAccountId}/sync")
    @Operation(summary = "Sincronizza transazioni", description = "Avvia la sincronizzazione asincrona delle transazioni bancarie collegate al conto locale")
    public ResponseEntity<String> syncBankTransactions(
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @RequestBody SyncBankTransactionsRequest request,
            @AuthenticationPrincipal User currentUser) {

        logger.info("Avvio sincronizzazione asincrona per account ID: {}", localAccountId);
        accountService.syncAccountTransactionWithGocardless(localAccountId, currentUser, request);

        return ResponseEntity.accepted().body("Sincronizzazione avviata in background");
    }



}
