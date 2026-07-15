package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.*;
import it.iacovelli.nexabudgetbe.dto.bank.*;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.BankProvider;
import it.iacovelli.nexabudgetbe.service.GocardlessService;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Adatta il {@link GocardlessService} esistente (invariato) all'astrazione {@link BankAggregationProvider}.
 */
@Component
public class GocardlessAggregationProvider implements BankAggregationProvider {

    private final GocardlessService gocardlessService;

    public GocardlessAggregationProvider(GocardlessService gocardlessService) {
        this.gocardlessService = gocardlessService;
    }

    @Override
    public BankProvider getProvider() {
        return BankProvider.GOCARDLESS;
    }

    @Override
    public List<BankInstitutionDto> getInstitutions(String countryCode) {
        return gocardlessService.getBanks(countryCode).stream()
                .map(this::toInstitutionDto)
                .toList();
    }

    private BankInstitutionDto toInstitutionDto(GocardlessBank bank) {
        return BankInstitutionDto.builder()
                .id(bank.getId())
                .name(bank.getName())
                .bic(bank.getBic())
                .countries(bank.getCountries())
                .logo(bank.getLogo())
                .maxAccessValidForDays(bank.getMaxAccessValidForDays())
                .build();
    }

    @Override
    public BankLinkResult startLink(String institutionId, UUID localAccountId) {
        GocardlessCreateWebToken token = gocardlessService.generateBankLinkForToken(institutionId, localAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Servizio GoCardless non disponibile, riprovare più tardi"));
        return BankLinkResult.builder()
                .redirectUrl(token.getLink())
                .providerReference(token.getRequisitionId())
                .build();
    }

    @Override
    public BankLinkCompletionResult completeLink(UUID localAccountId, String providerReference, String code) {
        // GoCardless non usa uno scambio code->sessione: il collegamento si verifica per polling
        // su getProviderAccounts dopo che l'utente ha completato il consenso presso la banca.
        return getProviderAccounts(providerReferenceAsAccount(providerReference));
    }

    private Account providerReferenceAsAccount(String requisitionId) {
        Account account = new Account();
        account.setRequisitionId(requisitionId);
        return account;
    }

    @Override
    public BankLinkCompletionResult getProviderAccounts(Account account) {
        GocardlessGetAccountsResponse response = gocardlessService.getBankAccounts(account.getRequisitionId());
        GocardlessGetAccounts data = response != null ? response.getData() : null;
        List<GocardlessBankDetail> details = (data != null && data.getAccounts() != null)
                ? data.getAccounts()
                : Collections.emptyList();

        List<NormalizedBankAccount> normalized = details.stream()
                .map(d -> NormalizedBankAccount.builder()
                        .providerAccountId(d.getAccount_id())
                        .name(d.getName())
                        .institutionName(d.getInstitution() != null ? d.getInstitution().getName() : null)
                        .build())
                .toList();

        return BankLinkCompletionResult.builder()
                .providerReference(account.getRequisitionId())
                .accounts(normalized)
                .build();
    }

    @Override
    public List<NormalizedBankTransaction> fetchTransactions(Account account, LocalDate startDate) {
        List<GocardlessTransaction> transactions =
                gocardlessService.getGoCardlessTransaction(account.getRequisitionId(), account.getExternalAccountId());

        return transactions.stream().map(this::toNormalized).toList();
    }

    private NormalizedBankTransaction toNormalized(GocardlessTransaction gt) {
        String rawDate = gt.getValueDate() != null ? gt.getValueDate() : gt.getBookingDate();
        List<String> remittance = gt.getRemittanceInformationUnstructuredArray();
        String remittanceInfo = (remittance != null && !remittance.isEmpty()) ? remittance.get(0) : null;

        return NormalizedBankTransaction.builder()
                .externalId(gt.getTransactionId())
                .amount(new java.math.BigDecimal(gt.getTransactionAmount().getAmount()))
                .currency(gt.getTransactionAmount().getCurrency())
                .date(rawDate)
                .creditorName(gt.getCreditorName())
                .debtorName(gt.getDebtorName())
                .remittanceInformation(remittanceInfo)
                .payeeName(gt.getPayeeName())
                .build();
    }
}
