package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.Transaction;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Riallinea le transazioni bancarie di un conto rimuovendo (soft delete, recuperabili dal cestino) i duplicati
 * accumulati dai sync precedenti. Eseguito a ogni sync, dopo l'import, con l'elenco booked appena ricevuto
 * dal provider come fonte di verità.
 * <p>
 * Due casi:
 * <ol>
 *   <li><b>Stesso externalId</b> più volte sullo stesso conto: si tiene una sola riga (preferendo quella
 *       categorizzata, poi la più vecchia).</li>
 *   <li><b>Pending orfane</b>: righe importate in passato come pending, il cui id non compare più tra le booked
 *       perché la banca ne ha assegnato uno nuovo alla contabilizzazione. Una riga è considerata orfana
 *       duplicata solo se:
 *       <ul>
 *         <li>ha un externalId non presente tra le booked ricevute;</li>
 *         <li>non è un trasferimento né un import da file (CSV/OFX);</li>
 *         <li>la sua data cade nella finestra coperta dal provider (con margine) ed è più vecchia di
 *             {@link #PENDING_GRACE_DAYS} giorni, così una pending ancora in attesa non viene toccata;</li>
 *         <li>esiste una booked salvata con stesso tipo e importo, entro {@link #MATCH_TOLERANCE_DAYS}
 *             giorni, creata dopo di lei.</li>
 *       </ul>
 *   </li>
 * </ol>
 * Categoria e nota della riga rimossa vengono trasferite alla riga superstite se questa non le ha.
 */
@Service
public class BankDuplicateReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(BankDuplicateReconciliationService.class);

    /** Differenza massima di data tra una pending e la corrispondente booked. */
    static final int MATCH_TOLERANCE_DAYS = 5;
    /** Età minima di una riga orfana prima di poterla considerare un duplicato (le pending durano pochi giorni). */
    static final int PENDING_GRACE_DAYS = 10;

    private final TransactionRepository transactionRepository;

    public BankDuplicateReconciliationService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * @param bookedFromProvider transazioni booked appena ricevute dal provider, con externalId già valorizzati
     *                           (vedi {@link BankTransactionIds#ensureExternalIds})
     * @return numero di righe duplicate rimosse
     */
    @Transactional
    public int reconcile(Account account, List<NormalizedBankTransaction> bookedFromProvider) {
        return reconcile(account, bookedFromProvider, null);
    }

    /**
     * @param requestedFrom data da cui il provider ha restituito le transazioni (sync incrementale), {@code null} se
     *                      la lista copre l'intero storico. Le righe salvate prima di questa data non sono nella lista
     *                      ricevuta e non vanno considerate orfane (es. una riga con value date antecedente al
     *                      date_from porterebbe il minimo della lista prima della finestra realmente richiesta).
     */
    @Transactional
    public int reconcile(Account account, List<NormalizedBankTransaction> bookedFromProvider, LocalDate requestedFrom) {
        Map<UUID, Transaction> toUpdate = new LinkedHashMap<>();
        Set<UUID> toDelete = new LinkedHashSet<>();

        removeSameExternalIdDuplicates(account, toUpdate, toDelete);
        removeOrphanPendingDuplicates(account, bookedFromProvider, requestedFrom, toUpdate, toDelete);

        toDelete.forEach(toUpdate::remove);
        if (!toUpdate.isEmpty()) {
            transactionRepository.saveAll(toUpdate.values());
        }
        if (!toDelete.isEmpty()) {
            transactionRepository.softDeleteByIds(toDelete, LocalDateTime.now());
            logger.info("[BankReconcile] Rimossi {} duplicati sul conto {}", toDelete.size(), account.getId());
        }
        return toDelete.size();
    }

    private void removeSameExternalIdDuplicates(Account account, Map<UUID, Transaction> toUpdate, Set<UUID> toDelete) {
        Map<String, List<Transaction>> byExternalId = transactionRepository.findDuplicatedExternalIdsByAccount(account).stream()
                .collect(Collectors.groupingBy(Transaction::getExternalId));

        Comparator<Transaction> keeperFirst = Comparator
                .comparing((Transaction t) -> t.getCategory() == null)
                .thenComparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Transaction::getId);

        byExternalId.forEach((externalId, rows) -> {
            List<Transaction> sorted = rows.stream().sorted(keeperFirst).toList();
            Transaction keeper = sorted.get(0);
            for (Transaction duplicate : sorted.subList(1, sorted.size())) {
                logger.info("[BankReconcile] Duplicato con stesso externalId {} sul conto {}: rimossa {} (tenuta {})",
                        externalId, account.getId(), duplicate.getId(), keeper.getId());
                mergeInto(keeper, duplicate, toUpdate);
                toDelete.add(duplicate.getId());
            }
        });
    }

    private void removeOrphanPendingDuplicates(Account account, List<NormalizedBankTransaction> booked,
                                               LocalDate requestedFrom,
                                               Map<UUID, Transaction> toUpdate, Set<UUID> toDelete) {
        Set<String> bookedIds = booked.stream()
                .map(NormalizedBankTransaction::getExternalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<LocalDate> bookedDates = booked.stream()
                .map(NormalizedBankTransaction::getDate)
                .filter(Objects::nonNull)
                .map(LocalDate::parse)
                .toList();
        if (bookedIds.isEmpty() || bookedDates.isEmpty()) {
            return;
        }

        LocalDate minDate = bookedDates.stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate maxDate = bookedDates.stream().max(Comparator.naturalOrder()).orElseThrow();
        LocalDate coveredFrom = requestedFrom != null && requestedFrom.isAfter(minDate) ? requestedFrom : minDate;
        LocalDate orphanFrom = coveredFrom.plusDays(MATCH_TOLERANCE_DAYS);
        LocalDate orphanTo = LocalDate.now().minusDays(PENDING_GRACE_DAYS);

        List<Transaction> candidates = transactionRepository.findWithExternalIdByAccountAndDateBetween(
                        account, minDate.minusDays(MATCH_TOLERANCE_DAYS), maxDate.plusDays(MATCH_TOLERANCE_DAYS)).stream()
                .filter(t -> !toDelete.contains(t.getId()))
                .toList();

        List<Transaction> survivors = new ArrayList<>();
        List<Transaction> orphans = new ArrayList<>();
        for (Transaction t : candidates) {
            if (bookedIds.contains(t.getExternalId())) {
                survivors.add(t);
            } else if (t.getTransferId() == null && t.getImportHash() == null
                    && !t.getDate().isBefore(orphanFrom) && !t.getDate().isAfter(orphanTo)) {
                orphans.add(t);
            }
        }

        for (Transaction orphan : orphans) {
            Optional<Transaction> match = survivors.stream()
                    .filter(s -> s.getType() == orphan.getType())
                    .filter(s -> s.getAmount().compareTo(orphan.getAmount()) == 0)
                    .filter(s -> daysBetween(s, orphan) <= MATCH_TOLERANCE_DAYS)
                    .filter(s -> createdBefore(orphan, s))
                    .min(Comparator.comparingLong(s -> daysBetween(s, orphan)));

            if (match.isPresent()) {
                logger.info("[BankReconcile] Pending orfana {} (externalId {}, {} {} del {}) duplicata della booked {} sul conto {}: rimossa",
                        orphan.getId(), orphan.getExternalId(), orphan.getType(), orphan.getAmount(), orphan.getDate(),
                        match.get().getExternalId(), account.getId());
                mergeInto(match.get(), orphan, toUpdate);
                toDelete.add(orphan.getId());
            } else {
                logger.debug("[BankReconcile] Riga {} (externalId {}) assente tra le booked e senza corrispondenza: lasciata invariata",
                        orphan.getId(), orphan.getExternalId());
            }
        }
    }

    private static long daysBetween(Transaction a, Transaction b) {
        return Math.abs(ChronoUnit.DAYS.between(a.getDate(), b.getDate()));
    }

    /** La copia duplicata è sempre stata importata prima della booked superstite; createdAt assente = non verificabile. */
    private static boolean createdBefore(Transaction orphan, Transaction survivor) {
        if (orphan.getCreatedAt() == null || survivor.getCreatedAt() == null) {
            return true;
        }
        return orphan.getCreatedAt().isBefore(survivor.getCreatedAt());
    }

    private static void mergeInto(Transaction keeper, Transaction duplicate, Map<UUID, Transaction> toUpdate) {
        boolean changed = false;
        if (keeper.getCategory() == null && duplicate.getCategory() != null) {
            keeper.setCategory(duplicate.getCategory());
            changed = true;
        }
        if ((keeper.getNote() == null || keeper.getNote().isBlank()) && duplicate.getNote() != null && !duplicate.getNote().isBlank()) {
            keeper.setNote(duplicate.getNote());
            changed = true;
        }
        if (changed) {
            toUpdate.put(keeper.getId(), keeper);
        }
    }
}
