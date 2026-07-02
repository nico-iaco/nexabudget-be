package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class GocardlessBankTransaction {
    private List<GocardlessBalance> balances;
    private String institutionId;
    private int startingBalance;
    private GocardlessTransactions transactions;

    /** Valorizzato solo in caso di errore (es. "expired"); assente in risposta di successo. */
    private String status;
    @JsonProperty("error_type")
    private String errorType;
    @JsonProperty("error_code")
    private String errorCode;
    private String reason;
    /** true = la requisition può essere rinnovata chiamando POST /bank/link. */
    private Boolean renewable;
    /** Codice raw GoCardless: EX (expired), RJ (rejected), SU (suspended)… */
    private String requisitionStatus;
}
