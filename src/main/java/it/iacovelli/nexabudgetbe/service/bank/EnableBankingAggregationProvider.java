package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.bank.*;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingAspsp;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingTransaction;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingSessionResponse;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.BankProvider;
import it.iacovelli.nexabudgetbe.service.EnableBankingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Adatta {@link EnableBankingService} (Cloud API) all'astrazione {@link BankAggregationProvider}.
 * A differenza di GoCardless, il collegamento avviene in due passi: {@link #startLink} apre il consenso
 * presso la banca, {@link #completeLink} scambia il {@code code} ricevuto in callback con una sessione
 * ({@code session_id}, salvato su Account.requisitionId) e i conti autorizzati.
 */
@Component
public class EnableBankingAggregationProvider implements BankAggregationProvider {

    private final EnableBankingService enableBankingService;

    @Value("${enablebanking.redirectUrl}")
    private String redirectUrl;

    @Value("${enablebanking.consentValidDays:90}")
    private int consentValidDays;

    public EnableBankingAggregationProvider(EnableBankingService enableBankingService) {
        this.enableBankingService = enableBankingService;
    }

    @Override
    public BankProvider getProvider() {
        return BankProvider.ENABLE_BANKING;
    }

    /**
     * Enable Banking è un provider opzionale: se ENABLEBANKING_APP_ID/ENABLEBANKING_PRIVATE_KEY
     * non sono configurati (o non validi), EnableBankingService.init() disabilita il servizio senza
     * impedire l'avvio dell'app (GoCardless resta comunque disponibile). Questo è l'unico punto in
     * cui lo stato viene verificato: nessuno dei metodi sotto invoca EnableBankingService se non
     * dopo questo controllo, quindi currentToken() non dovrebbe mai vedere configured=false.
     */
    private void requireConfigured() {
        if (!enableBankingService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Enable Banking non configurato in questo ambiente (ENABLEBANKING_APP_ID/ENABLEBANKING_PRIVATE_KEY mancanti)");
        }
    }

    @Override
    public List<BankInstitutionDto> getInstitutions(String countryCode) {
        requireConfigured();
        return enableBankingService.getAspsps(countryCode).stream()
                .map(this::toInstitutionDto)
                .toList();
    }

    private BankInstitutionDto toInstitutionDto(EnableBankingAspsp aspsp) {
        return BankInstitutionDto.builder()
                .id(aspsp.getName() + "|" + aspsp.getCountry())
                .name(aspsp.getName())
                .countries(aspsp.getCountry() != null ? List.of(aspsp.getCountry()) : List.of())
                .logo(aspsp.getLogo())
                .maxAccessValidForDays(aspsp.getMaximumConsentValidity() != null ? aspsp.getMaximumConsentValidity() : consentValidDays)
                .build();
    }

    /**
     * {@code institutionId} è nel formato {@code "<nomeAspsp>|<paese>"} così com'è restituito da
     * {@link #getInstitutions}: Enable Banking identifica un ASPSP con la coppia name+country, non
     * con un id singolo come GoCardless. Il controller compone questo id a partire dalla lista banche.
     */
    @Override
    public BankLinkResult startLink(String institutionId, java.util.UUID localAccountId) {
        requireConfigured();
        String[] parts = institutionId.split("\\|", 2);
        String aspspName = parts[0];
        String aspspCountry = parts.length > 1 ? parts[1] : null;

        String state = localAccountId.toString();
        String url = enableBankingService.startAuthorization(aspspName, aspspCountry, redirectUrl, state, consentValidDays);

        // Nessun providerReference disponibile finché l'utente non completa il consenso e arriva il code:
        // il session_id verrà salvato in completeLink().
        return BankLinkResult.builder()
                .redirectUrl(url)
                .providerReference(null)
                .build();
    }

    @Override
    public BankLinkCompletionResult completeLink(java.util.UUID localAccountId, String providerReference, String code) {
        requireConfigured();
        EnableBankingSessionResponse session = enableBankingService.createSession(code);

        List<NormalizedBankAccount> accounts = session.getAccounts() != null
                ? session.getAccounts().stream().map(a -> NormalizedBankAccount.builder()
                        .providerAccountId(a.getUid())
                        .name(a.getName())
                        .currency(a.getCurrency())
                        .iban(a.getAccountId() != null ? a.getAccountId().getIban() : null)
                        .build()).toList()
                : List.of();

        return BankLinkCompletionResult.builder()
                .providerReference(session.getSessionId())
                .accounts(accounts)
                .build();
    }

    @Override
    public BankLinkCompletionResult getProviderAccounts(Account account) {
        requireConfigured();
        // Enable Banking non espone un endpoint di poll separato: i conti sono noti solo al momento
        // della creazione della sessione (completeLink). Una volta collegato l'account locale,
        // basta l'externalAccountId (uid) già salvato per sincronizzare le transazioni.
        return BankLinkCompletionResult.builder()
                .providerReference(account.getRequisitionId())
                .accounts(List.of())
                .build();
    }

    @Override
    public List<NormalizedBankTransaction> fetchTransactions(Account account, LocalDate startDate) {
        requireConfigured();
        String dateFrom = startDate != null ? startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) : null;
        List<EnableBankingTransaction> transactions = enableBankingService.getTransactions(account.getExternalAccountId(), dateFrom);
        return transactions.stream().map(this::toNormalized).toList();
    }

    private NormalizedBankTransaction toNormalized(EnableBankingTransaction t) {
        String rawAmount = t.getTransactionAmount() != null ? t.getTransactionAmount().getAmount() : "0";
        BigDecimal amount = new BigDecimal(rawAmount).abs();
        if ("DBIT".equalsIgnoreCase(t.getCreditDebitIndicator())) {
            amount = amount.negate();
        }
        String date = t.getValueDate() != null ? t.getValueDate() : t.getBookingDate();
        String remittance = (t.getRemittanceInformation() != null && !t.getRemittanceInformation().isEmpty())
                ? t.getRemittanceInformation().get(0) : null;

        return NormalizedBankTransaction.builder()
                .externalId(t.getEntryReference())
                .amount(amount)
                .currency(t.getTransactionAmount() != null ? t.getTransactionAmount().getCurrency() : null)
                .date(date)
                .creditorName(t.getCreditor() != null ? t.getCreditor().getName() : null)
                .debtorName(t.getDebtor() != null ? t.getDebtor().getName() : null)
                .remittanceInformation(remittance)
                .build();
    }
}
