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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
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
    public ResponseEntity<String> getBankLink(
            @RequestBody BankLinkRequest body,
            @AuthenticationPrincipal User currentUser) {
        String institutionId = body.getInstitutionId();
        UUID localAccountId = body.getLocalAccountId();
        logger.debug("Received request to get bank link for institutionId: {} and localAccountId: {}", institutionId, localAccountId);
        accountService.getAccountByIdAndUser(localAccountId, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));
        GocardlessCreateWebToken bankLink = gocardlessService.generateBankLinkForToken(institutionId, localAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Servizio GoCardless non disponibile, riprovare più tardi"));
        accountService.addRequisitionIdToAccount(localAccountId, bankLink.getRequisitionId());
        return ResponseEntity.ok(bankLink.getLink());
    }

    @GetMapping("/bank/{localAccountId}/account")
    @Operation(summary = "Ottieni conti bancari", description = "Ottieni i conti bancari associati alla banca richiesta. " +
            "Quando linkedStatus != 'linked' il campo requiresReauth/renewable indica se rilanciare POST /bank/link.")
    public ResponseEntity<BankAccountsResponse> getBankAccounts(
            @Parameter(description = "ID conto") @PathVariable UUID localAccountId,
            @AuthenticationPrincipal User currentUser) {
        String requisitionIdForAccount = accountService.getRequisitionIdForAccount(localAccountId, currentUser);
        GocardlessGetAccountsResponse rawResponse = gocardlessService.getBankAccounts(requisitionIdForAccount);
        return ResponseEntity.ok(mapToBankAccountsResponse(rawResponse));
    }

    private BankAccountsResponse mapToBankAccountsResponse(GocardlessGetAccountsResponse response) {
        if (response == null) {
            logger.warn("[GoCardless] Risposta nulla da getBankAccounts, stato sconosciuto");
            return BankAccountsResponse.builder()
                    .linkedStatus("unknown")
                    .renewable(false)
                    .requiresReauth(false)
                    .accounts(Collections.emptyList())
                    .build();
        }

        GocardlessGetAccounts data = response.getData();
        List<GocardlessBankDetail> accounts = (data != null && data.getAccounts() != null)
                ? data.getAccounts()
                : Collections.emptyList();

        // Requisition linkata: ci sono account reali
        if (!accounts.isEmpty()) {
            return BankAccountsResponse.builder()
                    .linkedStatus("linked")
                    .renewable(false)
                    .requiresReauth(false)
                    .accounts(accounts)
                    .build();
        }

        // Requisition non linkata: propaghiamo lo stato dal microservizio
        String linkedStatus = response.getLinkedStatus() != null ? response.getLinkedStatus() : "unknown";
        boolean renewable = Boolean.TRUE.equals(response.getRenewable());

        logger.warn("[GoCardless] Requisition non linkata — linkedStatus: {}, requisitionStatus: {}, renewable: {}",
                linkedStatus, response.getRequisitionStatus(), renewable);

        // Per "pending" il microservizio restituisce il link esistente (nel campo data.link)
        String pendingLink = "pending".equals(linkedStatus) && data != null ? data.getLink() : null;

        return BankAccountsResponse.builder()
                .linkedStatus(linkedStatus)
                .requisitionStatus(response.getRequisitionStatus())
                .renewable(renewable)
                .requiresReauth(renewable)
                .link(pendingLink)
                .errorCode(response.getErrorCode())
                .reason(response.getReason())
                .accounts(Collections.emptyList())
                .build();
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
