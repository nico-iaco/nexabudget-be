package it.iacovelli.nexabudgetbe.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Risposta arricchita di GET /api/gocardless/bank/{localAccountId}/account.
 *
 * <ul>
 *   <li><b>linked</b>: requisition attiva, {@code accounts} è valorizzato.</li>
 *   <li><b>expired / rejected / suspended</b>: {@code requiresReauth=true},
 *       il FE deve richiamare POST /api/gocardless/bank/link per ottenere un nuovo link.</li>
 *   <li><b>pending</b>: auth avviata ma non completata, il FE deve
 *       reindirizzare l'utente al {@code link} esistente.</li>
 *   <li><b>unknown</b>: stato non riconosciuto, usare {@code reason} per dettagli.</li>
 * </ul>
 */
@Data
@Builder
public class BankAccountsResponse {
    /** "linked" | "expired" | "rejected" | "suspended" | "pending" | "unknown" */
    private String linkedStatus;
    /** Codice raw GoCardless (EX, RJ, SU, CR…) — utile per log/telemetria. */
    private String requisitionStatus;
    /** true = la requisition può essere rinnovata chiamando POST /bank/link. */
    private boolean renewable;
    /** Alias semantico di {@code renewable} per il frontend. */
    private boolean requiresReauth;
    /** Valorizzato solo quando {@code linkedStatus == "pending"}: link di redirect alla requisition esistente. */
    private String link;
    private String errorCode;
    private String reason;
    /** Valorizzato solo quando {@code linkedStatus == "linked"}. */
    private List<GocardlessBankDetail> accounts;
}
