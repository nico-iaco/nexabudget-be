package it.iacovelli.nexabudgetbe.exception;

/**
 * Sollevata quando il gocardless-integrator segnala che la requisition non è più valida
 * (es. error_code ITEM_LOGIN_REQUIRED, requisitionStatus EX) e serve un nuovo consenso utente.
 * Non è una RestClientException: non ha senso ritentare, serve rinnovare il collegamento.
 * Estende {@link BankReauthRequiredException} così il dispatch provider-agnostico in
 * AccountService può gestire GoCardless ed Enable Banking con lo stesso catch.
 */
public class GocardlessRequisitionExpiredException extends BankReauthRequiredException {

    public GocardlessRequisitionExpiredException(String message, String errorCode, String requisitionStatus, boolean renewable) {
        super(message, errorCode, requisitionStatus, renewable);
    }

    public String getRequisitionStatus() {
        return getProviderStatus();
    }
}
