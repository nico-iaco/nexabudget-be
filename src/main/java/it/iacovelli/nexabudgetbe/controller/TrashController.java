package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.TrashTransactionView;
import it.iacovelli.nexabudgetbe.service.TrashService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/trash")
@Tag(name = "Cestino", description = "Gestione elementi eliminati con possibilità di ripristino (30 giorni)")
public class TrashController {

    private final TrashService trashService;

    public TrashController(TrashService trashService) {
        this.trashService = trashService;
    }

    @GetMapping("/transactions")
    @Operation(summary = "Transazioni nel cestino", description = "Elenco delle transazioni soft-eliminate recuperabili")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> getDeletedTransactions(
            @AuthenticationPrincipal User currentUser) {
        List<TrashTransactionView> deleted = trashService.getDeletedTransactions(currentUser);
        List<TransactionDto.TransactionResponse> response = deleted.stream()
                .map(t -> TransactionDto.TransactionResponse.builder()
                        .id(t.getId())
                        .accountId(t.getAccountId())
                        .accountName(t.getAccountName())
                        .categoryId(t.getCategoryId())
                        .categoryName(t.getCategoryName())
                        .amount(t.getAmount())
                        .type(t.getType() != null ? TransactionType.valueOf(t.getType()) : null)
                        .description(t.getDescription())
                        .date(t.getDate())
                        .note(t.getNote())
                        .transferId(t.getTransferId())
                        .exchangeRate(t.getExchangeRate())
                        .originalCurrency(t.getOriginalCurrency())
                        .originalAmount(t.getOriginalAmount())
                        .deletedAt(t.getDeletedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/accounts")
    @Operation(summary = "Conti nel cestino", description = "Elenco dei conti soft-eliminati recuperabili")
    public ResponseEntity<List<AccountDto.TrashAccountResponse>> getDeletedAccounts(
            @AuthenticationPrincipal User currentUser) {
        List<Account> deleted = trashService.getDeletedAccounts(currentUser);
        List<AccountDto.TrashAccountResponse> response = deleted.stream()
                .map(a -> AccountDto.TrashAccountResponse.builder()
                        .id(a.getId())
                        .name(a.getName())
                        .currency(a.getCurrency())
                        .type(a.getType())
                        .deletedAt(a.getDeletedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/transactions/{id}/restore")
    @Operation(summary = "Ripristina transazione", description = "Ripristina una transazione dal cestino")
    public ResponseEntity<Void> restoreTransaction(
            @Parameter(description = "ID transazione") @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser) {
        trashService.restoreTransaction(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/accounts/{id}/restore")
    @Operation(summary = "Ripristina conto", description = "Ripristina un conto e le sue transazioni dal cestino")
    public ResponseEntity<Void> restoreAccount(
            @Parameter(description = "ID conto") @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser) {
        trashService.restoreAccount(id, currentUser);
        return ResponseEntity.noContent().build();
    }
}
