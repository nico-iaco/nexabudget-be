package it.iacovelli.nexabudgetbe.dto.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Esito del completamento del collegamento (scambio del code Enable Banking, o poll GoCardless).
 * {@code providerReference} va salvato su Account.requisitionId, gli {@code accounts} sono i conti
 * lato provider tra cui l'utente sceglie quello da associare all'Account locale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankLinkCompletionResult {
    private String providerReference;
    private List<NormalizedBankAccount> accounts;
}
