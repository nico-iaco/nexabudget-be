package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.Transaction;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AccountService;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import it.iacovelli.nexabudgetbe.service.TransactionService;
import it.iacovelli.nexabudgetbe.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Transazioni", description = "Gestione transazioni singole e trasferimenti tra conti")
public class TransactionController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;
    private final UserService userService;
    private final AccountService accountService;
    private final CategoryService categoryService;

    public TransactionController(TransactionService transactionService,
                                 UserService userService,
                                 AccountService accountService,
                                 CategoryService categoryService) {
        this.transactionService = transactionService;
        this.userService = userService;
        this.accountService = accountService;
        this.categoryService = categoryService;
    }

    @PostMapping
    @Operation(summary = "Crea transazione", description = "Crea una nuova transazione (income/expense)")
    public ResponseEntity<TransactionDto.TransactionResponse> createTransaction(
            @Valid @RequestBody TransactionDto.TransactionRequest transactionRequest,
            @AuthenticationPrincipal User currentUser) {

        logger.debug("Richiesta creazione transazione per account ID: {} da utente: {}", 
                transactionRequest.getAccountId(), currentUser.getUsername());

        // Usa il nuovo metodo per ottenere l'entità Account
        Account account = accountService.getAccountEntityByIdAndUser(transactionRequest.getAccountId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        Category category = null;
        if (transactionRequest.getCategoryId() != null) {
            category = categoryService.getCategoryByIdAndUser(transactionRequest.getCategoryId(), currentUser)
                    .orElse(null); // Permetti categoria nulla se non trovata (o lancia eccezione se preferisci)
        }

        Transaction transaction = Transaction.builder()
                .user(currentUser)
                .account(account)
                .category(category)
                .amount(transactionRequest.getAmount())
                .type(transactionRequest.getType())
                .description(transactionRequest.getDescription())
                .date(transactionRequest.getDate())
                .note(transactionRequest.getNote())
                .build();

        TransactionDto.TransactionResponse response = transactionService.createTransaction(transaction);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/transfer")
    @Operation(summary = "Crea trasferimento", description = "Crea un trasferimento tra due conti")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> createTransfer(
            @Valid @RequestBody TransactionDto.TransferRequest transferRequest,
            @AuthenticationPrincipal User currentUser) {

        Account sourceAccount = accountService.getAccountEntityByIdAndUser(transferRequest.getSourceAccountId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto di origine non trovato"));

        Account destinationAccount = accountService.getAccountEntityByIdAndUser(transferRequest.getDestinationAccountId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto di destinazione non trovato"));

        List<TransactionDto.TransactionResponse> responses = transactionService.createTransfer(
                sourceAccount,
                destinationAccount,
                transferRequest.getAmount(),
                transferRequest.getDescription(),
                transferRequest.getTransferDate(),
                transferRequest.getNotes()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }

    @PostMapping("/convert-to-transfer")
    @Operation(summary = "Converte due transazioni in un trasferimento", description = "Converte due transazioni singole esistenti in un trasferimento, collegandole.")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> convertToTransfer(
            @Valid @RequestBody TransactionDto.ConvertToTransferRequest request,
            @AuthenticationPrincipal User currentUser) {

        Transaction firstTransaction = transactionService.getTransactionByIdAndUser(request.getSourceTransactionId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Prima transazione non trovata"));

        Transaction secondTransaction = transactionService.getTransactionByIdAndUser(request.getDestinationTransactionId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seconda transazione non trovata"));

        try {
            List<TransactionDto.TransactionResponse> responses = transactionService.convertTransactionsToTransfer(firstTransaction, secondTransaction);
            return ResponseEntity.ok(responses);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "Transazioni utente", description = "Lista di tutte le transazioni dell'utente")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> getTransactionsByUserId(@AuthenticationPrincipal User currentUser) {
        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));
        // Chiama direttamente il metodo che restituisce DTO
        List<TransactionDto.TransactionResponse> transactions = transactionService.getTransactionsByUser(user);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Transazioni conto", description = "Transazioni associate ad un conto")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> getTransactionsByAccountId(@Parameter(description = "ID conto") @PathVariable UUID accountId,
                                                                                               @AuthenticationPrincipal User currentUser) {
        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));
        // Usa getAccountEntityByIdAndUser per ottenere l'entità
        Account account = accountService.getAccountEntityByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        // Chiama direttamente il metodo che restituisce DTO
        List<TransactionDto.TransactionResponse> transactions = transactionService.getTransactionsByAccount(account);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/category/{categoryId}")
    @Operation(summary = "Transazioni categoria", description = "Transazioni associate ad una categoria")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> getTransactionsByCategoryId(@Parameter(description = "ID categoria") @PathVariable UUID categoryId,
                                                                                                @AuthenticationPrincipal User currentUser) {
        Category category = categoryService.getCategoryById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria non trovata"));

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        // Chiama direttamente il metodo che restituisce DTO
        List<TransactionDto.TransactionResponse> transactions = transactionService.getTransactionsByCategoryAndUser(category, user);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/transfer/{transferId}")
    @Operation(summary = "Dettaglio trasferimento", description = "Transazioni collegate ad un trasferimento tramite transferId")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> getTransactionsByTransferId(@Parameter(description = "ID trasferimento") @PathVariable String transferId,
                                                                                                @AuthenticationPrincipal User currentUser) {
        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        // Chiama direttamente il metodo che restituisce DTO
        List<TransactionDto.TransactionResponse> transactions = transactionService.getTransactionsByTransferId(transferId, user);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/daterange")
    @Operation(summary = "Transazioni per periodo (utente)", description = "Transazioni utente in un intervallo temporale")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> getTransactionsByDateRange(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Data inizio (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "Data fine (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        // Chiama direttamente il metodo che restituisce DTO
        List<TransactionDto.TransactionResponse> transactions = transactionService.getTransactionsByUserAndDateRange(user, start, end);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/account/{accountId}/daterange")
    @Operation(summary = "Transazioni per periodo (conto)", description = "Transazioni di un conto in un intervallo temporale")
    public ResponseEntity<List<TransactionDto.TransactionResponse>> getTransactionsByAccountAndDateRange(
            @Parameter(description = "ID conto") @PathVariable UUID accountId,
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "Data/ora inizio (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "Data/ora fine (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        // Usa getAccountEntityByIdAndUser per ottenere l'entità
        Account account = accountService.getAccountEntityByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        // Chiama direttamente il metodo che restituisce DTO
        List<TransactionDto.TransactionResponse> transactions = transactionService.getTransactionsByAccountAndDateRange(account, start, end);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/account/{accountId}/income")
    @Operation(summary = "Totale entrate conto", description = "Somma entrate per un conto in un intervallo")
    public ResponseEntity<BigDecimal> getIncomeForAccountInPeriod(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID conto") @PathVariable UUID accountId,
            @Parameter(description = "Data/ora inizio (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @Parameter(description = "Data/ora fine (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        // Usa getAccountEntityByIdAndUser per ottenere l'entità
        Account account = accountService.getAccountEntityByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        BigDecimal income = transactionService.getIncomeForAccountInPeriod(account, start, end);
        return ResponseEntity.ok(income);
    }

    @GetMapping("/account/{accountId}/expense")
    @Operation(summary = "Totale uscite conto", description = "Somma uscite per un conto in un intervallo")
    public ResponseEntity<BigDecimal> getExpenseForAccountInPeriod(
            @AuthenticationPrincipal User currentUser,
            @Parameter(description = "ID conto") @PathVariable UUID accountId,
            @Parameter(description = "Data/ora inizio (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @Parameter(description = "Data/ora fine (ISO)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        User user = userService.getUserById(currentUser.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utente non trovato"));

        // Usa getAccountEntityByIdAndUser per ottenere l'entità
        Account account = accountService.getAccountEntityByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        BigDecimal expense = transactionService.getExpenseForAccountInPeriod(account, start, end);
        return ResponseEntity.ok(expense);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Aggiorna transazione", description = "Aggiorna una transazione esistente. Se fa parte di un trasferimento, aggiorna anche la transazione collegata.")
    public ResponseEntity<TransactionDto.TransactionResponse> updateTransaction(@PathVariable UUID id,
                                                                                @Valid @RequestBody TransactionDto.TransactionRequest transactionRequest,
                                                                                @AuthenticationPrincipal User currentUser) {
        Transaction oldTransaction = transactionService.getTransactionByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transazione non trovata"));

        Account account = accountService.getAccountEntityByIdAndUser(transactionRequest.getAccountId(), currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));

        Category category = null;
        if (transactionRequest.getCategoryId() != null) {
            category = categoryService.getCategoryByIdAndUser(transactionRequest.getCategoryId(), currentUser)
                    .orElse(null);
        }

        TransactionDto.TransactionResponse updatedTransactionResponse = transactionService.updateTransaction(
                oldTransaction,
                account,
                category,
                transactionRequest.getAmount(),
                transactionRequest.getType(),
                transactionRequest.getDescription(),
                transactionRequest.getDate(),
                transactionRequest.getNote()
        );

        return ResponseEntity.ok(updatedTransactionResponse);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina transazione", description = "Elimina una transazione per ID. Se fa parte di un trasferimento, elimina anche la transazione collegata.")
    public ResponseEntity<Void> deleteTransaction(@Parameter(description = "ID transazione") @PathVariable UUID id,
                                                  @AuthenticationPrincipal User currentUser) {

        Transaction transaction = transactionService.getTransactionByIdAndUser(id, currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transazione non trovata"));

        transactionService.deleteTransaction(transaction);
        return ResponseEntity.noContent().build();
    }
}
