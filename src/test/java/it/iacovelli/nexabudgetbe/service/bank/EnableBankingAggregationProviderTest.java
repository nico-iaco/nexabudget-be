package it.iacovelli.nexabudgetbe.service.bank;

import it.iacovelli.nexabudgetbe.dto.bank.NormalizedBankTransaction;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingAmount;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingParty;
import it.iacovelli.nexabudgetbe.dto.enablebanking.EnableBankingTransaction;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.BankProvider;
import it.iacovelli.nexabudgetbe.service.EnableBankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit test della mappatura EnableBankingTransaction -> NormalizedBankTransaction: verifica
 * che il segno derivi da credit_debit_indicator, la data preferisca value_date su booking_date,
 * e la descrizione sia risolta da remittance_information.
 */
@ExtendWith(MockitoExtension.class)
class EnableBankingAggregationProviderTest {

    @Mock
    private EnableBankingService enableBankingService;

    private EnableBankingAggregationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new EnableBankingAggregationProvider(enableBankingService);
    }

    @Test
    void getProvider_returnsEnableBanking() {
        assertEquals(BankProvider.ENABLE_BANKING, provider.getProvider());
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
