package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetAlertEmailContext;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.BudgetAlertRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private BudgetAlertService budgetAlertService;

    private User user;
    private Category category;
    private BudgetTemplate template;
    private Budget budget;
    private BudgetAlert alert;

    @BeforeEach
    void setUp() {
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
        when(budgetAlertRepository.findByActive(true)).thenReturn(List.of(alert));
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, LocalDate.now()))
                .thenReturn(Optional.of(budget));
    }

    @Test
    void checkAlerts_WhenUsageAboveThresholdAndNeverNotified_SendsEmailAndSetsLastNotifiedAt() {
        stubActiveAlertAndBudget();
        when(transactionRepository.sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(450)); // 90% of 500
        when(emailService.sendBudgetAlertEmail(any(BudgetAlertEmailContext.class))).thenReturn(true);

        budgetAlertService.checkAlerts();

        assertNotNull(alert.getLastNotifiedAt());
        verify(emailService, times(1)).sendBudgetAlertEmail(any(BudgetAlertEmailContext.class));
        verify(budgetAlertRepository, times(1)).save(alert);
    }

    @Test
    void checkAlerts_WhenAlreadyNotifiedThisPeriodAndStillAboveThreshold_DoesNotSendAgain() {
        alert.setLastNotifiedAt(LocalDateTime.now().minusHours(1));
        stubActiveAlertAndBudget();
        when(transactionRepository.sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(480)); // 96% of 500, still above threshold

        budgetAlertService.checkAlerts();

        verify(emailService, never()).sendBudgetAlertEmail(any());
        verify(budgetAlertRepository, never()).save(any());
    }

    @Test
    void checkAlerts_WhenUsageDropsBelowThresholdAfterNotification_ResetsLastNotifiedAt() {
        alert.setLastNotifiedAt(LocalDateTime.now().minusHours(1));
        stubActiveAlertAndBudget();
        // spend dropped below threshold, e.g. after re-categorizing a transaction out of this category
        when(transactionRepository.sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(200)); // 40% of 500, below threshold

        budgetAlertService.checkAlerts();

        assertNull(alert.getLastNotifiedAt());
        verify(emailService, never()).sendBudgetAlertEmail(any());
        verify(budgetAlertRepository, times(1)).save(alert);
    }

    @Test
    void checkAlerts_WhenRearmedAndUsageCrossesThresholdAgain_SendsNewNotification() {
        // Simulates: notified -> transaction moved out (drops below threshold, re-armed) ->
        // transaction moved back in / new spend (crosses threshold again) -> second notification sent
        alert.setLastNotifiedAt(null); // already re-armed by a prior run
        stubActiveAlertAndBudget();
        when(transactionRepository.sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(450)); // 90% of 500, crosses threshold again
        when(emailService.sendBudgetAlertEmail(any(BudgetAlertEmailContext.class))).thenReturn(true);

        budgetAlertService.checkAlerts();

        assertNotNull(alert.getLastNotifiedAt());
        verify(emailService, times(1)).sendBudgetAlertEmail(any(BudgetAlertEmailContext.class));
    }

    @Test
    void checkAlerts_WhenUsageBelowThresholdAndNeverNotified_DoesNothing() {
        stubActiveAlertAndBudget();
        when(transactionRepository.sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(100)); // 20% of 500

        budgetAlertService.checkAlerts();

        assertNull(alert.getLastNotifiedAt());
        verify(emailService, never()).sendBudgetAlertEmail(any());
        verify(budgetAlertRepository, never()).save(any());
    }

    @Test
    void checkAlerts_WhenNoActiveBudgetFound_SkipsAlert() {
        when(budgetAlertRepository.findByActive(true)).thenReturn(List.of(alert));
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, LocalDate.now()))
                .thenReturn(Optional.empty());

        budgetAlertService.checkAlerts();

        verify(transactionRepository, never()).sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any());
        verify(emailService, never()).sendBudgetAlertEmail(any());
    }

    @Test
    void checkAlerts_WhenBudgetLimitIsZero_SkipsAlert() {
        budget.setBudgetLimit(BigDecimal.ZERO);
        stubActiveAlertAndBudget();
        when(transactionRepository.sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(100));

        budgetAlertService.checkAlerts();

        verify(emailService, never()).sendBudgetAlertEmail(any());
        verify(budgetAlertRepository, never()).save(any());
    }

    @Test
    void checkAlerts_WhenEmailSendFails_DoesNotSetLastNotifiedAt() {
        stubActiveAlertAndBudget();
        when(transactionRepository.sumNetByUserAndCategoryAndDateRange(any(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(450));
        when(emailService.sendBudgetAlertEmail(any(BudgetAlertEmailContext.class))).thenReturn(false);

        budgetAlertService.checkAlerts();

        assertNull(alert.getLastNotifiedAt());
        verify(budgetAlertRepository, never()).save(any());
    }
}
