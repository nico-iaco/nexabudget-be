package it.iacovelli.nexabudgetbe.exception;

import lombok.Getter;

/**
 * Sollevata quando il gocardless-integrator segnala che la requisition non è più valida
 * (es. error_code ITEM_LOGIN_REQUIRED, requisitionStatus EX) e serve un nuovo consenso utente.
 * Non è una RestClientException: non ha senso ritentare, serve rinnovare il collegamento.
 */
@Getter
public class GocardlessRequisitionExpiredException extends RuntimeException {

    private final String errorCode;
    private final String requisitionStatus;
    private final boolean renewable;

    public GocardlessRequisitionExpiredException(String message, String errorCode, String requisitionStatus, boolean renewable) {
        super(message);
        this.errorCode = errorCode;
        this.requisitionStatus = requisitionStatus;
        this.renewable = renewable;
    }
}
