package it.iacovelli.nexabudgetbe.dto.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Conto esposto dal provider (GoCardless account, Enable Banking account uid) prima del collegamento
 * definitivo a un {@link it.iacovelli.nexabudgetbe.model.Account} locale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedBankAccount {
    /** Id del conto lato provider — da salvare come Account.externalAccountId al momento del link. */
    private String providerAccountId;
    private String name;
    private String iban;
    private String currency;
    private String institutionName;
}
