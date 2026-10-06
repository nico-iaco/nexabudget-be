package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Garantisce che ogni {@link NormalizedBankTransaction} abbia un {@code externalId} prima della dedup.
 * <p>
 * Alcune banche non valorizzano l'id transazione: senza fallback la dedup cercherebbe
 * {@code external_id IS NULL}, intercettando le transazioni manuali del conto (e fallendo con più risultati).
 * Il fallback è uno SHA-256 deterministico di conto, data, importo con segno e campi descrittivi; transazioni
 * identiche nello stesso batch (es. due caffè uguali nello stesso giorno) vengono distinte da un suffisso
 * di occorrenza, stabile finché la banca restituisce lo stesso elenco.
 */
public final class BankTransactionIds {

    static final String HASH_PREFIX = "nxb:";

    private BankTransactionIds() {
    }

    /** Valorizza in-place gli externalId mancanti. Idempotente: gli id già presenti non vengono toccati. */
    public static void ensureExternalIds(List<NormalizedBankTransaction> transactions, UUID accountId) {
        Map<String, Integer> occurrences = new HashMap<>();
        for (NormalizedBankTransaction nt : transactions) {
            if (nt.getExternalId() != null && !nt.getExternalId().isBlank()) {
                continue;
            }
            String key = String.join("|",
                    String.valueOf(accountId),
                    Objects.toString(nt.getDate(), ""),
                    nt.getAmount() != null ? nt.getAmount().stripTrailingZeros().toPlainString() : "",
                    Objects.toString(nt.getCreditorName(), ""),
                    Objects.toString(nt.getDebtorName(), ""),
                    Objects.toString(nt.getRemittanceInformation(), ""),
                    Objects.toString(nt.getPayeeName(), ""));
            int occurrence = occurrences.merge(key, 1, Integer::sum);
            nt.setExternalId(HASH_PREFIX + sha256(key + "#" + occurrence));
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }
}
