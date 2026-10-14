package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.config.TestConfig;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.AccountService;
import it.iacovelli.nexabudgetbe.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Il saldo di chiusura dell'andamento saldo deve coincidere con il saldo totale dei conti, trasferimenti inclusi. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class BalanceTrendConsistencyTest {

    @Autowired private ReportService reportService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    void closingBalance_matchesTotalBalance_withTransferLegs() {
        transactionRepository.hardDeleteAll();
        accountRepository.hardDeleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .username("trenduser").email("trend@example.com").passwordHash("x").defaultCurrency("EUR").build());
        Account a = account(user, "A");
        Account b = account(user, "B");
        LocalDate today = LocalDate.now();

        tx(user, a, "1000", TransactionType.IN, today.minusMonths(2), null);
        tx(user, a, "100", TransactionType.OUT, today.minusDays(1), "t1");
        tx(user, b, "90", TransactionType.IN, today.minusDays(1), "t1");

        ReportDto.BalanceTrendResponse trend = reportService.getBalanceTrend(user, today.minusMonths(3), today);
        BigDecimal closing = trend.getItems().get(trend.getItems().size() - 1).getClosingBalance();

        assertEquals(0, accountService.getTotalConvertedBalance(user).compareTo(closing));
    }

    private Account account(User user, String name) {
        return accountRepository.save(Account.builder().name(name).type(AccountType.CONTO_CORRENTE)
                .currency("EUR").user(user).build());
    }

    private void tx(User user, Account acc, String amount, TransactionType type, LocalDate date, String transferId) {
        transactionRepository.saveAndFlush(Transaction.builder().user(user).account(acc)
                .amount(new BigDecimal(amount)).type(type).description("t").date(date).transferId(transferId).build());
    }
}
