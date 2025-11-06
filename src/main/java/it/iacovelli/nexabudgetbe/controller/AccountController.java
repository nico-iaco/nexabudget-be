package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Conti", description = "Gestione dei conti dell'utente")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Crea conto", description = "Crea un nuovo conto per l'utente loggato")
    public ResponseEntity<AccountDto.AccountResponse> createAccount(@Valid @RequestBody AccountDto.AccountRequest accountRequest,
                                                                    @AuthenticationPrincipal User currentUser) {

        AccountDto.AccountResponse createdAccount = accountService.createAccount(accountRequest, accountRequest.getStarterBalance(), currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdAccount);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Recupera conto", description = "Ritorna i dettagli di un conto per ID")
    public ResponseEntity<AccountDto.AccountResponse> getAccountById(@Parameter(description = "ID del conto") @PathVariable Long id,
                                                                     @AuthenticationPrincipal User currentUser) {
        AccountDto.AccountResponse account = accountService.getAccountByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));
        return ResponseEntity.ok(account);
    }

    @GetMapping("/")
    @Operation(summary = "Lista conti utente", description = "Ritorna tutti i conti dell'utente")
    public ResponseEntity<List<AccountDto.AccountResponse>> getAccountsByUserId(@AuthenticationPrincipal User currentUser) {

        List<AccountDto.AccountResponse> accounts = new ArrayList<>(accountService.getAccountsByUser(currentUser));
        return ResponseEntity.ok(accounts);
    }

    @GetMapping("/type/{type}")
    @Operation(summary = "Conti per tipo", description = "Ritorna i conti filtrati per tipo")
    public ResponseEntity<List<AccountDto.AccountResponse>> getAccountsByUserAndType(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Tipo di conto") @PathVariable AccountType type) {

        List<AccountDto.AccountResponse> accounts = accountService.getAccountsByUserAndType(currentUser, type);
        return ResponseEntity.ok(accounts);
    }


    @GetMapping("/currency/{currency}")
    @Operation(summary = "Conti per valuta", description = "Ritorna i conti dell'utente filtrati per valuta")
    public ResponseEntity<List<AccountDto.AccountResponse>> getAccountsByUserAndCurrency(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Valuta del conto") @PathVariable String currency) {

        List<AccountDto.AccountResponse> accounts = accountService.getAccountsByUserAndCurrency(currentUser, currency);
        return ResponseEntity.ok(accounts);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna conto", description = "Aggiorna i dati di un conto")
    public ResponseEntity<AccountDto.AccountResponse> updateAccount(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID del conto") @PathVariable Long id,
            @Valid @RequestBody AccountDto.AccountRequest accountRequest) {

        AccountDto.AccountResponse updatedAccountResponse = accountService.updateAccount(
                id,
                currentUser,
                accountRequest.getName(),
                accountRequest.getType(),
                accountRequest.getCurrency()
        );

        return ResponseEntity.ok(updatedAccountResponse);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina conto", description = "Elimina un conto e tutte le sue transazioni")
    public ResponseEntity<Void> deleteAccount(@Parameter(description = "ID del conto") @PathVariable Long id,
                                              @AuthenticationPrincipal User currentUser) {
        // Verifica che l'utente sia il proprietario del conto prima di eliminarlo
        accountService.getAccountByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        accountService.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/total-balance")
    @Operation(summary = "Saldo totale per valuta", description = "Calcola il saldo totale di tutti i conti per una data valuta")
    public ResponseEntity<BigDecimal> getTotalBalance(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Valuta per il calcolo del saldo") @RequestParam String currency) {

        BigDecimal totalBalance = accountService.getTotalBalance(currentUser, currency);
        return ResponseEntity.ok(totalBalance);
    }

    @GetMapping("/total/{currency}")
    @Operation(summary = "Saldo totale", description = "Calcola il saldo totale dell'utente per una certa valuta")
    public ResponseEntity<BigDecimal> getTotalBalanceByUserAndCurrency(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Codice valuta ISO") @PathVariable String currency) {

        BigDecimal totalBalance = accountService.getTotalBalance(currentUser, currency);
        return ResponseEntity.ok(totalBalance);
    }

}
