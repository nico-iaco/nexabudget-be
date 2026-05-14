package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BulkCategorizationStatusResponse;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.Transaction;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkCategorizationService {

    private final TransactionRepository transactionRepository;
    private final AiCategorizationService aiCategorizationService;

    @Value("${nexabudget.ai.bulk.categorization.timeout-seconds:90}")
    private int aiCallTimeoutSeconds;

    // In-memory store: non dipende da Redis, non blocca mai su rete
    private final ConcurrentHashMap<UUID, BulkCategorizationStatusResponse> jobStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> jobOwners = new ConcurrentHashMap<>();

    public BulkCategorizationStatusResponse startBulkCategorizationJob(User user) {
        List<Transaction> uncategorized = transactionRepository.findUncategorizedByUser(user);
        if (uncategorized.isEmpty()) {
            throw new IllegalStateException("Nessuna transazione senza categoria trovata");
        }

        UUID jobId = UUID.randomUUID();
        BulkCategorizationStatusResponse initial = new BulkCategorizationStatusResponse(jobId, "PENDING", uncategorized.size(), 0, 0);
        jobStore.put(jobId, initial);
        jobOwners.put(jobId, user.getId());
        return initial;
    }

    @Async
    public void executeBulkCategorization(UUID jobId, User user) {
        List<Transaction> uncategorized = transactionRepository.findUncategorizedByUser(user);
        int total = uncategorized.size();
        int processed = 0;
        int categorized = 0;

        jobStore.put(jobId, new BulkCategorizationStatusResponse(jobId, "IN_PROGRESS", total, 0, 0));
        log.info("[BulkCategorization] Job {} avviato: {} transazioni (timeout per chiamata: {}s)", jobId, total, aiCallTimeoutSeconds);

        try {
            for (Transaction tx : uncategorized) {
                Optional<Category> category = categorizeWithTimeout(jobId, tx, user);
                try {
                    if (category.isPresent()) {
                        transactionRepository.updateCategoryById(tx.getId(), category.get().getId());
                        categorized++;
                    }
                } catch (Throwable t) {
                    log.warn("[BulkCategorization] Job {} - errore salvataggio transazione {} ({}): {}",
                            jobId, tx.getId(), t.getClass().getSimpleName(), t.getMessage());
                }

                processed++;
                jobStore.put(jobId, new BulkCategorizationStatusResponse(jobId, "IN_PROGRESS", total, processed, categorized));
            }

            jobStore.put(jobId, new BulkCategorizationStatusResponse(jobId, "COMPLETED", total, processed, categorized));
            log.info("[BulkCategorization] Job {} completato: {}/{} transazioni categorizzate", jobId, categorized, total);

        } catch (Throwable t) {
            log.error("[BulkCategorization] Job {} terminato con errore inatteso dopo {}/{} transazioni",
                    jobId, processed, total, t);
            jobStore.put(jobId, new BulkCategorizationStatusResponse(jobId, "FAILED", total, processed, categorized));
        }
    }

    /**
     * Esegue la categorizzazione AI in un thread separato con un timeout esplicito.
     * Necessario perché OkHttp 4.x usa synchronized blocks che pinnano i virtual thread,
     * impedendo l'interruzione tramite callTimeout nativo del SDK.
     */
    private Optional<Category> categorizeWithTimeout(UUID jobId, Transaction tx, User user) {
        CompletableFuture<Optional<Category>> future = CompletableFuture.supplyAsync(
                () -> aiCategorizationService.categorizeTransaction(tx.getDescription(), user, tx.getType()));
        try {
            return future.get(aiCallTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[BulkCategorization] Job {} - timeout ({}s) su '{}', transazione saltata",
                    jobId, aiCallTimeoutSeconds, tx.getDescription());
        } catch (ExecutionException e) {
            log.warn("[BulkCategorization] Job {} - errore AI su '{}' ({}): {}",
                    jobId, tx.getDescription(), e.getCause().getClass().getSimpleName(), e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[BulkCategorization] Job {} interrotto durante categorizzazione di '{}'", jobId, tx.getDescription());
        }
        return Optional.empty();
    }

    public BulkCategorizationStatusResponse getJobStatus(UUID jobId, User user) {
        BulkCategorizationStatusResponse status = jobStore.get(jobId);
        if (status == null) {
            throw new IllegalArgumentException("Job non trovato o scaduto");
        }
        UUID owner = jobOwners.get(jobId);
        if (owner != null && !owner.equals(user.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Accesso non autorizzato al job");
        }
        return status;
    }
}
