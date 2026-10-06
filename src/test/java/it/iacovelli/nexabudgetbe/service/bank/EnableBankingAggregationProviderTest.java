package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.bank.BankInstitutionDto;
import it.iacovelli.nexabudgetbe.dto.bank.BankLinkCompletionResult;
import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankAccount;
import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingAmount;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingAccount;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingAspsp;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingAspspRef;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingParty;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingSessionResponse;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingTransaction;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.BankProvider;
import it.iacovelli.nexabudgetbe.service.EnableBankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test della mappatura EnableBankingTransaction -> NormalizedBankTransaction: verifica
 * che il segno derivi da credit_debit_indicator, la data preferisca value_date su booking_date,
 * e la descrizione sia risolta da remittance_information. Copre anche il fallback grazioso quando
 * Enable Banking non è configurato (provider opzionale, vedi EnableBankingService.isConfigured()).
 */
@ExtendWith(MockitoExtension.class)
class EnableBankingAggregationProviderTest {

    @Mock
    private EnableBankingService enableBankingService;

    private EnableBankingAggregationProvider provider;

    @BeforeEach
    void setUp() {
        // Configurato di default in tutti i test tranne quelli della sezione "not configured" sotto,
        // che sovrascrivono esplicitamente lo stub. lenient() evita fallimenti di strict-stubbing
        // per i test che non arrivano mai a controllare isConfigured() (es. getProvider()).
        lenient().when(enableBankingService.isConfigured()).thenReturn(true);
        provider = new EnableBankingAggregationProvider(enableBankingService);
        ReflectionTestUtils.setField(provider, "consentValidDays", 90);
        ReflectionTestUtils.setField(provider, "redirectUrl", "https://app.example/callback");
    }

    @Test
    void getProvider_returnsEnableBanking() {
        assertEquals(BankProvider.ENABLE_BANKING, provider.getProvider());
    }

    @Test
    void getInstitutions_encodesNameAndCountryIntoId() {
        EnableBankingAspsp aspsp = new EnableBankingAspsp();
        aspsp.setName("BBVA");
        aspsp.setCountry("IT");
        when(enableBankingService.getAspsps("IT")).thenReturn(List.of(aspsp));

        List<BankInstitutionDto> result = provider.getInstitutions("IT");

        assertEquals(1, result.size());
        // startLink() fa split("\\|") su questo id per ricavare aspspName/aspspCountry: senza il
        // country incluso qui, l'autorizzazione Enable Banking fallisce con 422 (country=null).
        assertEquals("BBVA|IT", result.get(0).getId());
    }

    @Test
    void getInstitutions_convertsMaximumConsentValidityFromSecondsToDays() {
        EnableBankingAspsp aspsp = aspsp("Isybank", "IT", 180 * 86_400);
        when(enableBankingService.getAspsps("IT")).thenReturn(List.of(aspsp));

        BankInstitutionDto dto = provider.getInstitutions("IT").get(0);

        assertEquals(180, dto.getMaxAccessValidForDays());
    }

    @Test
    void startLink_clampsConsentValidityToBankMaximum() {
        when(enableBankingService.getAspsps("IT")).thenReturn(List.of(aspsp("Banca Breve", "IT", 60 * 86_400)));
        when(enableBankingService.startAuthorization(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("https://bank.example/auth");

        provider.startLink("Banca Breve|IT", UUID.randomUUID());

        verify(enableBankingService).startAuthorization(eq("Banca Breve"), eq("IT"), anyString(), anyString(), eq(60));
    }

    @Test
    void startLink_usesConfiguredValidityWhenBankAllowsMore() {
        when(enableBankingService.getAspsps("IT")).thenReturn(List.of(aspsp("Isybank", "IT", 180 * 86_400)));
        when(enableBankingService.startAuthorization(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("https://bank.example/auth");

        provider.startLink("Isybank|IT", UUID.randomUUID());

        verify(enableBankingService).startAuthorization(eq("Isybank"), eq("IT"), anyString(), anyString(), eq(90));
    }

    @Test
    void fetchTransactions_creditTransaction_mapsToPositiveAmount() {
        EnableBankingTransaction credit = transaction("tx-1", "125.50", "CRDT", "2026-02-10", "2026-02-11", "Stipendio");
        when(enableBankingService.getTransactions(anyString(), any())).thenReturn(List.of(credit));

        Account account = accountWithUid("uid-1");
        List<NormalizedBankTransaction> result = provider.fetchTransactions(account, null);

        assertEquals(1, result.size());
        NormalizedBankTransaction nt = result.get(0);
        assertEquals("tx-1", nt.getExternalId());
        assertEquals(0, new BigDecimal("125.50").compareTo(nt.getAmount()));
        // value_date presente: preferita a booking_date
        assertEquals("2026-02-10", nt.getDate());
        assertEquals("Stipendio", nt.getRemittanceInformation());
    }

    @Test
    void fetchTransactions_debitTransaction_mapsToNegativeAmount() {
        EnableBankingTransaction debit = transaction("tx-2", "30.00", "DBIT", null, "2026-02-12", "Supermercato");
        when(enableBankingService.getTransactions(anyString(), any())).thenReturn(List.of(debit));

        Account account = accountWithUid("uid-1");
        List<NormalizedBankTransaction> result = provider.fetchTransactions(account, null);

        assertEquals(1, result.size());
        NormalizedBankTransaction nt = result.get(0);
        assertEquals(0, new BigDecimal("-30.00").compareTo(nt.getAmount()));
        // value_date assente: si ricade su booking_date
        assertEquals("2026-02-12", nt.getDate());
    }

    @Test
    void fetchTransactions_usesCreditorAndDebtorNamesWhenPresent() {
        EnableBankingTransaction t = transaction("tx-3", "10.00", "DBIT", "2026-02-13", "2026-02-13", "generic");
        EnableBankingParty creditor = new EnableBankingParty();
        creditor.setName("Comune di Milano");
        t.setCreditor(creditor);
        when(enableBankingService.getTransactions(anyString(), any())).thenReturn(List.of(t));

        Account account = accountWithUid("uid-1");
        NormalizedBankTransaction nt = provider.fetchTransactions(account, null).get(0);

        assertEquals("Comune di Milano", nt.getCreditorName());
    }

    @Test
    void getInstitutions_whenNotConfigured_throws503WithoutCallingService() {
        when(enableBankingService.isConfigured()).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> provider.getInstitutions("IT"));
        assertEquals(503, ex.getStatusCode().value());
        org.mockito.Mockito.verify(enableBankingService, org.mockito.Mockito.never()).getAspsps(anyString());
    }

    @Test
    void startLink_whenNotConfigured_throws503() {
        when(enableBankingService.isConfigured()).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> provider.startLink("BBVA|IT", UUID.randomUUID()));
    }

    @Test
    void completeLink_whenNotConfigured_throws503() {
        when(enableBankingService.isConfigured()).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> provider.completeLink(UUID.randomUUID(), null, "some-code"));
    }

    @Test
    void completeLink_mapsInstitutionNameFromSessionAspsp() {
        EnableBankingAccount account = new EnableBankingAccount();
        account.setUid("uid-1");
        account.setName("Conto corrente");
        EnableBankingSessionResponse session = new EnableBankingSessionResponse();
        session.setSessionId("session-1");
        session.setAccounts(List.of(account));
        session.setAspsp(new EnableBankingAspspRef("Intesa Sanpaolo", "IT"));
        when(enableBankingService.createSession("code-1")).thenReturn(session);

        BankLinkCompletionResult result = provider.completeLink(UUID.randomUUID(), null, "code-1");

        assertEquals("session-1", result.getProviderReference());
        NormalizedBankAccount normalized = result.getAccounts().get(0);
        assertEquals("uid-1", normalized.getProviderAccountId());
        assertEquals("Intesa Sanpaolo", normalized.getInstitutionName());
    }

    @Test
    void getProviderAccounts_whenNotConfigured_throws503() {
        when(enableBankingService.isConfigured()).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> provider.getProviderAccounts(accountWithUid("uid-1")));
    }

    @Test
    void fetchTransactions_whenNotConfigured_throws503() {
        when(enableBankingService.isConfigured()).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> provider.fetchTransactions(accountWithUid("uid-1"), LocalDate.now()));
    }

    @Test
    void fetchTransactions_skipsPendingTransactions() {
        EnableBankingTransaction booked = transaction("tx-book", "10.00", "DBIT", "2026-02-10", "2026-02-10", "Bar");
        booked.setStatus("BOOK");
        EnableBankingTransaction pending = transaction("tx-pdng", "20.00", "DBIT", "2026-02-11", null, "Supermercato");
        pending.setStatus("PDNG");
        EnableBankingTransaction noStatus = transaction("tx-none", "30.00", "DBIT", "2026-02-09", "2026-02-09", "Farmacia");
        when(enableBankingService.getTransactions(anyString(), any())).thenReturn(List.of(booked, pending, noStatus));

        List<NormalizedBankTransaction> result = provider.fetchTransactions(accountWithUid("uid-1"), null);

        assertEquals(List.of("tx-book", "tx-none"), result.stream().map(NormalizedBankTransaction::getExternalId).toList());
    }

    @Test
    void fetchTransactions_missingEntryReference_fallsBackToTransactionId() {
        EnableBankingTransaction t = transaction(null, "10.00", "DBIT", "2026-02-10", "2026-02-10", "Bar");
        t.setTransactionId("eb-tx-42");
        when(enableBankingService.getTransactions(anyString(), any())).thenReturn(List.of(t));

        List<NormalizedBankTransaction> result = provider.fetchTransactions(accountWithUid("uid-1"), null);

        assertEquals("eb-tx-42", result.get(0).getExternalId());
    }

    private EnableBankingAspsp aspsp(String name, String country, Integer maxConsentSeconds) {
        EnableBankingAspsp aspsp = new EnableBankingAspsp();
        aspsp.setName(name);
        aspsp.setCountry(country);
        aspsp.setMaximumConsentValidity(maxConsentSeconds);
        return aspsp;
    }

    private Account accountWithUid(String uid) {
        Account account = new Account();
        account.setExternalAccountId(uid);
        return account;
    }

    private EnableBankingTransaction transaction(String entryReference, String amount, String creditDebitIndicator,
                                                  String valueDate, String bookingDate, String remittance) {
        EnableBankingTransaction t = new EnableBankingTransaction();
        t.setEntryReference(entryReference);
        EnableBankingAmount txAmount = new EnableBankingAmount();
        txAmount.setAmount(amount);
        txAmount.setCurrency("EUR");
        t.setTransactionAmount(txAmount);
        t.setCreditDebitIndicator(creditDebitIndicator);
        t.setValueDate(valueDate);
        t.setBookingDate(bookingDate);
        t.setRemittanceInformation(List.of(remittance));
        return t;
    }
}
