package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.Transaction;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.TrashTransactionView;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TrashService {

    private static final Logger logger = LoggerFactory.getLogger(TrashService.class);
    private static final int TRASH_RETENTION_DAYS = 30;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TrashService(TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<TrashTransactionView> getDeletedTransactions(User user) {
        return transactionRepository.findDeletedByUserId(user.getId());
    }

    @Transactional(readOnly = true)
    public List<Account> getDeletedAccounts(User user) {
        return accountRepository.findDeletedByUserId(user.getId());
    }

    @Transactional
    public void restoreTransaction(UUID transactionId, User user) {
        Transaction transaction = transactionRepository.findDeletedByIdAndUserId(transactionId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transazione non trovata nel cestino"));

        // Un trasferimento viene cancellato su entrambe le gambe: va ripristinato allo stesso modo,
        // altrimenti un solo conto riavrebbe l'importo.
        List<UUID> accountIds = transaction.getTransferId() != null
                ? transactionRepository.findDeletedAccountIdsByTransferIdAndUserId(transaction.getTransferId(), user.getId())
                : List.of(transaction.getAccount().getId());
        for (UUID accountId : accountIds) {
            if (accountRepository.findDeletedByIdAndUserId(accountId, user.getId()).isPresent()) {
                throw new IllegalStateException("Il conto della transazione è nel cestino: ripristinare prima il conto");
            }
        }

        if (transaction.getTransferId() != null) {
            transactionRepository.restoreByTransferIdAndUserId(transaction.getTransferId(), user.getId());
        } else {
            transactionRepository.restoreById(transactionId);
        }
        logger.info("Transazione {} ripristinata dall'utente {}", transactionId, user.getId());
    }

    @Transactional
    public void restoreAccount(UUID accountId, User user) {
        Account account = accountRepository.findDeletedByIdAndUserId(accountId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conto non trovato nel cestino"));
        accountRepository.restoreById(accountId);
        // Solo le transazioni cancellate insieme al conto. La finestra di qualche secondo copre i conti
        // cancellati prima che transazioni e conto condividessero lo stesso timestamp.
        LocalDateTime deletedAt = account.getDeletedAt();
        if (deletedAt != null) {
            transactionRepository.restoreByAccountIdDeletedBetween(accountId,
                    deletedAt.minusSeconds(5), deletedAt.plusSeconds(1));
        } else {
            transactionRepository.restoreAllByAccountId(accountId);
        }
        logger.info("Conto {} e relative transazioni ripristinati dall'utente {}", accountId, user.getId());
    }

    @Transactional
    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeExpiredItems() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(TRASH_RETENTION_DAYS);
        int deletedTransactions = transactionRepository.purgeOldDeleted(cutoff);
        int deletedAccounts = accountRepository.purgeOldDeleted(cutoff);
        logger.info("Pulizia cestino completata: {} transazioni e {} conti eliminati definitivamente",
                deletedTransactions, deletedAccounts);
    }
}
