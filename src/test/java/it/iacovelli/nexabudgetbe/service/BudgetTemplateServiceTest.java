package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.BudgetAlertRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Job dei template budget: recupera i periodi saltati ed è idempotente sullo stesso periodo. */
@ExtendWith(MockitoExtension.class)
class BudgetTemplateServiceTest {

    @Mock
    private BudgetTemplateRepository budgetTemplateRepository;
    @Mock
    private BudgetRepository budgetRepository;
    @Mock
    private BudgetAlertRepository budgetAlertRepository;

    private ConcurrentMapCacheManager cacheManager;
    private BudgetTemplateService service;

    private User user;
    private Category category;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager();
        service = new BudgetTemplateService(budgetTemplateRepository, budgetRepository, budgetAlertRepository,
                cacheManager, mock(PlatformTransactionManager.class));
        user = User.builder().id(UUID.randomUUID()).username("tpl").email("tpl@example.com").build();
        category = Category.builder().id(UUID.randomUUID()).name("Spesa").user(user).build();
        lenient().when(budgetTemplateRepository.findByActiveAndRecurrenceType(eq(true), any())).thenReturn(List.of());
    }

    private BudgetTemplate template(RecurrenceType type) {
        BudgetTemplate t = BudgetTemplate.builder()
                .id(UUID.randomUUID()).user(user).category(category)
                .budgetLimit(new BigDecimal("300.00")).recurrenceType(type).active(true).build();
        when(budgetTemplateRepository.findByActiveAndRecurrenceType(true, type)).thenReturn(List.of(t));
        return t;
    }

    @Test
    void missedRunOnFirstDay_isRecoveredLaterWithFullPeriod() {
        template(RecurrenceType.MONTHLY);
        template(RecurrenceType.QUARTERLY);
        template(RecurrenceType.YEARLY);
        LocalDate today = LocalDate.of(2026, 10, 9); // il run del 1° ottobre è saltato
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, today)).thenReturn(Optional.empty());

        service.instantiateTemplates(today);

        ArgumentCaptor<Budget> saved = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository, times(3)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(b ->
                b.getStartDate().equals(LocalDate.of(2026, 10, 1)) && b.getEndDate().equals(LocalDate.of(2026, 10, 31))));
        assertTrue(saved.getAllValues().stream().anyMatch(b ->
                b.getStartDate().equals(LocalDate.of(2026, 10, 1)) && b.getEndDate().equals(LocalDate.of(2026, 12, 31))));
        assertTrue(saved.getAllValues().stream().anyMatch(b ->
                b.getStartDate().equals(LocalDate.of(2026, 1, 1)) && b.getEndDate().equals(LocalDate.of(2026, 12, 31))));
    }

    @Test
    void secondRunInSamePeriod_doesNothing_soDeletedBudgetsAreNotRecreated() {
        template(RecurrenceType.MONTHLY);
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(eq(user), eq(category), any())).thenReturn(Optional.empty());

        service.instantiateTemplates(LocalDate.of(2026, 10, 1));
        service.instantiateTemplates(LocalDate.of(2026, 10, 2)); // l'utente ha cancellato il budget nel frattempo

        verify(budgetRepository, times(1)).save(any(Budget.class));
        verify(budgetTemplateRepository, times(1)).findByActiveAndRecurrenceType(true, RecurrenceType.MONTHLY);
    }

    @Test
    void newPeriod_isInstantiatedAgain() {
        template(RecurrenceType.MONTHLY);
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(eq(user), eq(category), any())).thenReturn(Optional.empty());

        service.instantiateTemplates(LocalDate.of(2026, 10, 1));
        service.instantiateTemplates(LocalDate.of(2026, 11, 1));

        verify(budgetRepository, times(2)).save(any(Budget.class));
    }

    @Test
    void activeBudgetAlreadyPresent_isNotDuplicated() {
        template(RecurrenceType.MONTHLY);
        LocalDate today = LocalDate.of(2026, 10, 1);
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, today))
                .thenReturn(Optional.of(Budget.builder().id(UUID.randomUUID()).build()));

        service.instantiateTemplates(today);

        verify(budgetRepository, never()).save(any(Budget.class));
    }

    @Test
    void failedRun_leavesNoMarker_andIsRetried() {
        template(RecurrenceType.MONTHLY);
        LocalDate today = LocalDate.of(2026, 10, 1);
        when(budgetRepository.findActiveBudgetByUserAndCategoryAndDate(user, category, today))
                .thenThrow(new RuntimeException("DB down"))
                .thenReturn(Optional.empty());

        service.instantiateTemplates(today);
        assertNull(cacheManager.getCache(CacheConfig.BUDGET_TEMPLATE_RUNS_CACHE).get("MONTHLY:2026-10-01"));
        assertNull(cacheManager.getCache(CacheConfig.BUDGET_TEMPLATE_RUN_LOCK_CACHE).get("MONTHLY:2026-10-01"));

        service.instantiateTemplates(today);
        verify(budgetRepository, times(1)).save(any(Budget.class));
    }

    @Test
    void runClaimedByAnotherInstance_isSkipped() {
        cacheManager.getCache(CacheConfig.BUDGET_TEMPLATE_RUN_LOCK_CACHE).put("MONTHLY:2026-10-01", "running");

        service.instantiateTemplates(LocalDate.of(2026, 10, 1));

        verify(budgetTemplateRepository, never()).findByActiveAndRecurrenceType(true, RecurrenceType.MONTHLY);
    }

    @Test
    void periodStart_followsCalendarPeriods() {
        assertEquals(LocalDate.of(2026, 2, 1), BudgetTemplateService.periodStart(RecurrenceType.MONTHLY, LocalDate.of(2026, 2, 28)));
        assertEquals(LocalDate.of(2026, 1, 1), BudgetTemplateService.periodStart(RecurrenceType.QUARTERLY, LocalDate.of(2026, 3, 31)));
        assertEquals(LocalDate.of(2026, 10, 1), BudgetTemplateService.periodStart(RecurrenceType.QUARTERLY, LocalDate.of(2026, 12, 15)));
        assertEquals(LocalDate.of(2026, 1, 1), BudgetTemplateService.periodStart(RecurrenceType.YEARLY, LocalDate.of(2026, 7, 4)));
    }
}
