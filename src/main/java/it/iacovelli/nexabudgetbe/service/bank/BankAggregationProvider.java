package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.bank.BankInstitutionDto;
import it.iacovelli.nexabudgetbe.dto.bank.BankLinkCompletionResult;
import it.iacovelli.nexabudgetbe.dto.bank.BankLinkResult;
import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.BankProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Astrazione provider-agnostica per l'aggregazione bancaria (Open Banking/PSD2).
 * Implementata da {@link GocardlessAggregationProvider} ed {@link EnableBankingAggregationProvider};
 * {@link it.iacovelli.nexabudgetbe.service.AccountService} fa dispatch su {@link Account#getProvider()}.
 */
public interface BankAggregationProvider {

    BankProvider getProvider();

    /** Lista delle banche/ASPSP supportate per il paese richiesto. */
    List<BankInstitutionDto> getInstitutions(String countryCode);

    /**
     * Avvia il collegamento di un conto locale al provider: genera l'url di redirect verso il consenso utente.
     */
    BankLinkResult startLink(String institutionId, UUID localAccountId);

    /**
     * Completa il collegamento avviato da {@link #startLink}. Per GoCardless è un no-op (il collegamento
     * si completa tramite polling di {@link #getProviderAccounts}); per Enable Banking scambia il
     * {@code code} ricevuto in callback con una sessione e i conti autorizzati.
     */
    BankLinkCompletionResult completeLink(UUID localAccountId, String providerReference, String code);

    /** Conti disponibili presso il provider per la requisition/sessione già collegata all'Account. */
    BankLinkCompletionResult getProviderAccounts(Account account);

    /**
     * Recupera le transazioni dal provider per il conto collegato.
     * Lancia {@link it.iacovelli.nexabudgetbe.exception.BankReauthRequiredException} se il consenso non è più valido.
     */
    List<NormalizedBankTransaction> fetchTransactions(Account account, LocalDate startDate);
}
