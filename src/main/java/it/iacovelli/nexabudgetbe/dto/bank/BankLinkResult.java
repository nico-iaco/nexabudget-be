package it.iacovelli.nexabudgetbe.dto.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Esito dell'avvio del collegamento di un conto a un provider (redirect verso il consenso utente).
 * {@code providerReference} è ciò che va persistito su Account.requisitionId in attesa del completamento
 * (requisitionId per GoCardless, non ancora disponibile per Enable Banking finché non arriva il code).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankLinkResult {
    private String redirectUrl;
    private String providerReference;
}
