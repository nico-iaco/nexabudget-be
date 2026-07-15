package it.iacovelli.nexabudgetbe.exception;

import lombok.Getter;

/**
 * Sollevata da un {@link it.iacovelli.nexabudgetbe.service.bank.BankAggregationProvider} quando
 * il consenso/sessione dell'utente presso il provider bancario non è più valido (requisition scaduta,
 * sessione Enable Banking scaduta, ecc.) e serve un nuovo collegamento tramite il flusso di link.
 * Non è una RestClientException: non ha senso ritentare, serve rinnovare il consenso.
 */
@Getter
public class BankReauthRequiredException extends RuntimeException {

    private final String errorCode;
    private final String providerStatus;
    private final boolean renewable;

    public BankReauthRequiredException(String message, String errorCode, String providerStatus, boolean renewable) {
        super(message);
        this.errorCode = errorCode;
        this.providerStatus = providerStatus;
        this.renewable = renewable;
    }
}
