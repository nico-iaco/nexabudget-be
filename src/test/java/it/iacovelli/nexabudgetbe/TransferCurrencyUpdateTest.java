package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.ExchangeRateService;
import it.iacovelli.nexabudgetbe.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Modifica di una gamba di trasferimento: l'invariante IN.amount = OUT.amount × exchangeRate (campi di cambio
 * solo sulla gamba IN) deve reggere anche cambiando tipo o conto.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@org.springframework.transaction.annotation.Transactional
class TransferCurrencyUpdateTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    private User user;
    private Account eurAccount;
    private Account eurAccount2;
    private Account usdAccount;
    private Account gbpAccount;

    @BeforeEach
    void setUp() {
        transactionRepository.hardDeleteAll();
        accountRepository.hardDeleteAll();
        userRepository.deleteAll();

        user = userRepository.save(User.builder()
                .username("transferfx").email("transferfx@example.com").passwordHash("hash").build());
        eurAccount = account("EUR principale", "EUR");
        eurAccount2 = account("EUR secondario", "EUR");
        usdAccount = account("USD", "USD");
        gbpAccount = account("GBP", "GBP");

        when(exchangeRateService.getRate("EUR", "USD")).thenReturn(Optional.of(new BigDecimal("1.10")));
        when(exchangeRateService.getRate("EUR", "GBP")).thenReturn(Optional.of(new BigDecimal("0.85")));
    }

    @AfterEach
    void tearDown() {
        transactionRepository.hardDeleteAll();
        accountRepository.hardDeleteAll();
        userRepository.deleteAll();
    }

    private Account account(String name, String currency) {
        return accountRepository.save(Account.builder()
                .name(name).type(AccountType.CONTO_CORRENTE).currency(currency).user(user).build());
    }

    private Transaction leg(TransactionType type) {
        List<TransactionDto.TransactionResponse> legs = transactionService.createTransfer(
                eurAccount, usdAccount, new BigDecimal("100.00"), "Cambio", LocalDate.of(2026, 10, 1), null);
        String transferId = legs.get(0).getTransferId();
        return transactionRepository.findByTransferIdAndUser(transferId, user).stream()
                .filter(t -> t.getType() == type).findFirst().orElseThrow();
    }

    private Transaction otherLeg(Transaction t) {
        return transactionRepository.findByTransferIdAndUser(t.getTransferId(), user).stream()
                .filter(x -> !x.getId().equals(t.getId())).findFirst().orElseThrow();
    }

    private void update(Transaction t, Account account, String amount, TransactionType type) {
        transactionService.updateTransaction(t, account, null, new BigDecimal(amount), type,
                t.getDescription(), t.getDate(), t.getNote());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "atteso " + expected + " ma era " + actual);
    }

    @Test
    void editOutAmount_samePair_reusesStoredRate() {
        Transaction out = leg(TransactionType.OUT);

        update(out, eurAccount, "200.00", TransactionType.OUT);

        Transaction in = otherLeg(out);
        assertAmount("220.00", in.getAmount());
        assertAmount("1.10", in.getExchangeRate());
        assertEquals("EUR", in.getOriginalCurrency());
        assertAmount("200.00", in.getOriginalAmount());
        assertNull(out.getExchangeRate());
    }

    @Test
    void flipType_movesRateToNewInLegWithInverseRate() {
        Transaction out = leg(TransactionType.OUT); // EUR, ora diventa IN

        update(out, eurAccount, "100.00", TransactionType.IN);

        Transaction other = otherLeg(out); // USD, ora OUT
        assertEquals(TransactionType.OUT, other.getType());
        assertNull(other.getExchangeRate());
        assertNull(other.getOriginalCurrency());
        // USD -> EUR = 1 / 1.10: 100 EUR in arrivo valgono 110 USD in uscita
        assertAmount("110.0000", other.getAmount());
        assertEquals("USD", out.getOriginalCurrency());
        assertAmount("110.0000", out.getOriginalAmount());
        assertAmount("100.00", other.getAmount().multiply(out.getExchangeRate()).setScale(2, java.math.RoundingMode.HALF_UP));
        verify(exchangeRateService, never()).getRate("USD", "EUR");
    }

    @Test
    void moveInLegToSameCurrency_clearsExchangeFields() {
        Transaction in = leg(TransactionType.IN); // USD -> spostata su un conto EUR

        update(in, eurAccount2, "100.00", TransactionType.IN);

        Transaction out = otherLeg(in);
        assertAmount("100.00", out.getAmount());
        assertNull(in.getExchangeRate());
        assertNull(in.getOriginalCurrency());
        assertNull(in.getOriginalAmount());
    }

    @Test
    void moveInLegToNewCurrency_fetchesFreshRate() {
        Transaction in = leg(TransactionType.IN); // USD -> spostata su un conto GBP

        update(in, gbpAccount, "85.00", TransactionType.IN);

        Transaction out = otherLeg(in);
        assertAmount("100.0000", out.getAmount());
        assertAmount("0.85", in.getExchangeRate());
        assertEquals("EUR", in.getOriginalCurrency());
        assertAmount("100.0000", in.getOriginalAmount());
    }

    @Test
    void sameCurrencyTransferMovedToOtherCurrency_withoutRate_isRejected() {
        when(exchangeRateService.getRate(anyString(), anyString())).thenReturn(Optional.empty());
        List<TransactionDto.TransactionResponse> legs = transactionService.createTransfer(
                eurAccount, eurAccount2, new BigDecimal("50.00"), "Giroconto", LocalDate.of(2026, 10, 1), null);
        Transaction out = transactionRepository.findByTransferIdAndUser(legs.get(0).getTransferId(), user).stream()
                .filter(t -> t.getType() == TransactionType.OUT).findFirst().orElseThrow();

        assertThrows(IllegalStateException.class, () -> update(out, usdAccount, "50.00", TransactionType.OUT));
    }
}
