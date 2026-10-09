package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetAlertEmailContext;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.BudgetAlertRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetAlertServiceTest {

    @Mock
    private BudgetAlertRepository budgetAlertRepository;

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private ExchangeRateService exchangeRateService;

    private BudgetAlertService budgetAlertService;

    private User user;
    private Category category;
    private BudgetTemplate template;
    private Budget budget;
    private BudgetAlert alert;

    @BeforeEach
    void setUp() {
        // BudgetService reale sopra il repository mockato: l'alert usa lo stesso calcolo dello speso degli endpoint
        budgetAlertService = new BudgetAlertService(budgetAlertRepository, budgetRepository,
                new BudgetService(budgetRepository, transactionRepository, new CurrencyConversionService(exchangeRateService)),
                emailService, mock(PlatformTransactionManager.class));

        user = User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .defaultCurrency("EUR")
                .build();

        category = Category.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name("Alimentari")
                .build();

        template = BudgetTemplate.builder()
                .id(UUID.randomUUID())
                .user(user)
                .category(category)
                .budgetLimit(BigDecimal.valueOf(500))
                .recurrenceType(RecurrenceType.MONTHLY)
                .active(true)
                .build();

        budget = Budget.builder()
                .id(UUID.randomUUID())
                .user(user)
                .category(category)
                .budgetLimit(BigDecimal.valueOf(500))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .endDate(LocalDate.now().withDayOfMonth(28))
                .build();

        alert = BudgetAlert.builder()
                .id(UUID.randomUUID())
                .budgetTemplate(template)
                .user(user)
                .thresholdPercentage(80)
                .active(true)
                .build();
    }

    private void stubActiveAlertAndBudget() {
        when(budgetAlertRepository.findActiveIds()).thenReturn(List.of(alert.getId()));
        when(budgetAlertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, LocalDate.now()))
                .thenReturn(Optional.of(budget));
    }

    private void stubSpent(Object[]... rowsPerCurrency) {
        when(transactionRepository.sumNetByUserAndCategoryAndDateRangePerCurrency(any(), any(), any(), any()))
                .thenReturn(List.of(rowsPerCurrency));
    }

    private static Object[] net(String currency, long amount) {
        return new Object[]{currency, BigDecimal.valueOf(amount)};
    }

    private void stubEmailResult(boolean sent) {
        when(emailService.sendBudgetAlertEmailAsync(any(BudgetAlertEmailContext.class)))
                .thenReturn(CompletableFuture.completedFuture(sent));
    }

    @Test
    void checkAlerts_WhenUsageAboveThresholdAndNeverNotified_SendsEmailAndSetsLastNotifiedAt() {
        stubActiveAlertAndBudget();
        stubSpent(net("EUR", 450)); // 90% of 500
        stubEmailResult(true);

        budgetAlertService.checkAlerts();

        verify(emailService, times(1)).sendBudgetAlertEmailAsync(any(BudgetAlertEmailContext.class));
        verify(budgetAlertRepository, times(1)).updateLastNotifiedAt(eq(alert.getId()), notNull());
        verify(budgetAlertRepository, never()).save(any());
    }

    @Test
    void checkAlerts_WhenAlreadyNotifiedThisPeriodAndStillAboveThreshold_DoesNotSendAgain() {
        alert.setLastNotifiedAt(LocalDateTime.now().minusHours(1));
        stubActiveAlertAndBudget();
        stubSpent(net("EUR", 480)); // 96% of 500, still above threshold

        budgetAlertService.checkAlerts();

        verify(emailService, never()).sendBudgetAlertEmailAsync(any());
        verify(budgetAlertRepository, never()).updateLastNotifiedAt(any(), any());
    }

    @Test
    void checkAlerts_WhenUsageDropsBelowThresholdAfterNotification_ResetsLastNotifiedAt() {
        alert.setLastNotifiedAt(LocalDateTime.now().minusHours(1));
        stubActiveAlertAndBudget();
        // spend dropped below threshold, e.g. after re-categorizing a transaction out of this category
        stubSpent(net("EUR", 200)); // 40% of 500, below threshold

        budgetAlertService.checkAlerts();

        verify(emailService, never()).sendBudgetAlertEmailAsync(any());
        verify(budgetAlertRepository, times(1)).updateLastNotifiedAt(eq(alert.getId()), isNull());
    }

    @Test
    void checkAlerts_WhenRearmedAndUsageCrossesThresholdAgain_SendsNewNotification() {
        // Simulates: notified -> transaction moved out (drops below threshold, re-armed) ->
        // transaction moved back in / new spend (crosses threshold again) -> second notification sent
        alert.setLastNotifiedAt(null); // already re-armed by a prior run
        stubActiveAlertAndBudget();
        stubSpent(net("EUR", 450)); // 90% of 500, crosses threshold again
        stubEmailResult(true);

        budgetAlertService.checkAlerts();

        verify(emailService, times(1)).sendBudgetAlertEmailAsync(any(BudgetAlertEmailContext.class));
        verify(budgetAlertRepository, times(1)).updateLastNotifiedAt(eq(alert.getId()), notNull());
    }

    @Test
    void checkAlerts_WhenUsageBelowThresholdAndNeverNotified_DoesNothing() {
        stubActiveAlertAndBudget();
        stubSpent(net("EUR", 100)); // 20% of 500

        budgetAlertService.checkAlerts();

        verify(emailService, never()).sendBudgetAlertEmailAsync(any());
        verify(budgetAlertRepository, never()).updateLastNotifiedAt(any(), any());
    }

    @Test
    void checkAlerts_WhenNoActiveBudgetFound_SkipsAlert() {
        when(budgetAlertRepository.findActiveIds()).thenReturn(List.of(alert.getId()));
        when(budgetAlertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, LocalDate.now()))
                .thenReturn(Optional.empty());

        budgetAlertService.checkAlerts();

        verify(transactionRepository, never()).sumNetByUserAndCategoryAndDateRangePerCurrency(any(), any(), any(), any());
        verify(emailService, never()).sendBudgetAlertEmailAsync(any());
    }

    @Test
    void checkAlerts_WhenBudgetLimitIsZero_SkipsAlert() {
        budget.setBudgetLimit(BigDecimal.ZERO);
        stubActiveAlertAndBudget();

        budgetAlertService.checkAlerts();

        verify(emailService, never()).sendBudgetAlertEmailAsync(any());
        verify(budgetAlertRepository, never()).updateLastNotifiedAt(any(), any());
    }

    @Test
    void checkAlerts_WhenEmailSendFails_DoesNotSetLastNotifiedAt() {
        stubActiveAlertAndBudget();
        stubSpent(net("EUR", 450));
        stubEmailResult(false);

        budgetAlertService.checkAlerts();

        verify(budgetAlertRepository, never()).updateLastNotifiedAt(any(), any());
    }

    @Test
    void checkAlerts_SpentInOtherCurrency_IsConvertedToUserCurrency() {
        stubActiveAlertAndBudget();
        // 100 EUR + 400 USD (= 360 EUR) = 460 EUR -> 92% of 500: soglia superata solo grazie alla conversione
        stubSpent(net("EUR", 100), net("USD", 400));
        when(exchangeRateService.getRate("USD", "EUR")).thenReturn(Optional.of(new BigDecimal("0.90")));
        stubEmailResult(true);

        budgetAlertService.checkAlerts();

        verify(emailService).sendBudgetAlertEmailAsync(argThat(ctx -> ctx.getUsagePercent().compareTo(new BigDecimal("92.0")) == 0));
    }

    @Test
    void checkAlerts_WhenOneAlertFails_OtherAlertsAreStillChecked() {
        UUID brokenId = UUID.randomUUID();
        when(budgetAlertRepository.findActiveIds()).thenReturn(List.of(brokenId, alert.getId()));
        when(budgetAlertRepository.findById(brokenId)).thenThrow(new RuntimeException("DB error"));
        when(budgetAlertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, LocalDate.now()))
                .thenReturn(Optional.of(budget));
        stubSpent(net("EUR", 450));
        stubEmailResult(true);

        budgetAlertService.checkAlerts();

        verify(emailService, times(1)).sendBudgetAlertEmailAsync(any());
        verify(budgetAlertRepository).updateLastNotifiedAt(eq(alert.getId()), notNull());
    }

    @Test
    void checkAlerts_WhileEmailStillInFlight_DoesNotSendTwice() {
        stubActiveAlertAndBudget();
        stubSpent(net("EUR", 450));
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        when(emailService.sendBudgetAlertEmailAsync(any(BudgetAlertEmailContext.class))).thenReturn(pending);

        budgetAlertService.checkAlerts();
        budgetAlertService.checkAlerts(); // SMTP ancora lento: l'alert non va rivalutato

        verify(emailService, times(1)).sendBudgetAlertEmailAsync(any());

        pending.complete(true);
        verify(budgetAlertRepository).updateLastNotifiedAt(eq(alert.getId()), notNull());
    }
}
