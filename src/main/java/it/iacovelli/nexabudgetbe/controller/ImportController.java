package it.iacovelli.nexabudgetbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.iacovelli.nexabudgetbe.dto.ImportDto;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AccountService;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import it.iacovelli.nexabudgetbe.service.ImportService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountId}/import")
@Tag(name = "Import Transazioni", description = "Importazione transazioni da file CSV o OFX")
public class ImportController {

    private static final Logger logger = LoggerFactory.getLogger(ImportController.class);

    private final ImportService importService;
    private final AccountService accountService;
    private final CategoryService categoryService;

    public ImportController(ImportService importService,
                            AccountService accountService,
                            CategoryService categoryService) {
        this.importService = importService;
        this.accountService = accountService;
        this.categoryService = categoryService;
    }

    // ─── CSV ────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/csv/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Anteprima import CSV",
               description = "Analizza il file CSV e restituisce un'anteprima delle transazioni da importare con indicazione dei duplicati")
    public ResponseEntity<ImportDto.ImportPreviewResponse> previewCsv(
            @PathVariable UUID accountId,
            @RequestPart("file") MultipartFile file,
            @RequestPart("mapping") @Valid ImportDto.CsvColumnMapping mapping,
            @AuthenticationPrincipal User currentUser) {
        Account account = resolveAccount(accountId, currentUser);
        try {
            return ResponseEntity.ok(importService.previewCsv(file, mapping, account));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Errore nella lettura del file CSV: " + e.getMessage());
        }
    }

    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importa da CSV",
               description = "Importa le transazioni dal file CSV. Se selectedHashes è vuoto, importa tutti i non-duplicati.")
    public ResponseEntity<ImportDto.ImportResult> importCsv(
            @PathVariable UUID accountId,
            @RequestPart("file") MultipartFile file,
            @RequestPart("mapping") @Valid ImportDto.CsvColumnMapping mapping,
            @RequestPart(value = "confirm", required = false) ImportDto.ImportConfirmRequest confirm,
            @AuthenticationPrincipal User currentUser) {
        Account account = resolveAccount(accountId, currentUser);
        Category defaultCategory = resolveDefaultCategory(confirm, currentUser);
        try {
            ImportDto.ImportResult result = importService.importCsv(file, mapping, account, currentUser, confirm, defaultCategory);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Errore nella lettura del file CSV: " + e.getMessage());
        }
    }

    // ─── OFX ────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/ofx/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Anteprima import OFX",
               description = "Analizza il file OFX/QFX e restituisce un'anteprima delle transazioni da importare con indicazione dei duplicati")
    public ResponseEntity<ImportDto.ImportPreviewResponse> previewOfx(
            @PathVariable UUID accountId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser) {
        Account account = resolveAccount(accountId, currentUser);
        try {
            return ResponseEntity.ok(importService.previewOfx(file, account));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Errore nella lettura del file OFX: " + e.getMessage());
        }
    }

    @PostMapping(value = "/ofx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importa da OFX",
               description = "Importa le transazioni dal file OFX/QFX. Se selectedHashes è vuoto, importa tutti i non-duplicati.")
    public ResponseEntity<ImportDto.ImportResult> importOfx(
            @PathVariable UUID accountId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "confirm", required = false) ImportDto.ImportConfirmRequest confirm,
            @AuthenticationPrincipal User currentUser) {
        Account account = resolveAccount(accountId, currentUser);
        Category defaultCategory = resolveDefaultCategory(confirm, currentUser);
        try {
            ImportDto.ImportResult result = importService.importOfx(file, account, currentUser, confirm, defaultCategory);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Errore nella lettura del file OFX: " + e.getMessage());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private Account resolveAccount(UUID accountId, User user) {
        return accountService.getAccountEntityByIdAndUser(accountId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato"));
    }

    private Category resolveDefaultCategory(ImportDto.ImportConfirmRequest confirm, User user) {
        if (confirm != null && confirm.getDefaultCategoryId() != null) {
            return categoryService.getCategoryByIdAndUser(confirm.getDefaultCategoryId(), user)
                    .orElse(null);
        }
        return null;
    }
}
