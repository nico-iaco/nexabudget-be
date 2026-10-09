package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.BudgetAlertRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BudgetTemplateService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetTemplateService.class);

    private final BudgetTemplateRepository budgetTemplateRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetAlertRepository budgetAlertRepository;
    private final CacheManager cacheManager;
    private final TransactionTemplate transactionTemplate;

    public BudgetTemplateService(BudgetTemplateRepository budgetTemplateRepository,
                                  BudgetRepository budgetRepository,
                                  BudgetAlertRepository budgetAlertRepository,
                                  CacheManager cacheManager,
                                  PlatformTransactionManager transactionManager) {
        this.budgetTemplateRepository = budgetTemplateRepository;
        this.budgetRepository = budgetRepository;
        this.budgetAlertRepository = budgetAlertRepository;
        this.cacheManager = cacheManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public BudgetTemplate createTemplate(BudgetTemplate template) {
        BudgetTemplate saved = budgetTemplateRepository.save(template);
        if (Boolean.TRUE.equals(saved.getActive())) {
            upsertCurrentPeriodBudget(saved, LocalDate.now().withDayOfMonth(1));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<BudgetTemplate> getTemplatesByUser(User user) {
        return budgetTemplateRepository.findByUser(user);
    }

    @Transactional(readOnly = true)
    public Optional<BudgetTemplate> getTemplateByIdAndUser(UUID id, User user) {
        return budgetTemplateRepository.findByIdAndUser(id, user);
    }

    @Transactional
    public BudgetTemplate updateTemplate(BudgetTemplate template) {
        BudgetTemplate saved = budgetTemplateRepository.save(template);
        if (Boolean.TRUE.equals(saved.getActive())) {
            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            upsertCurrentPeriodBudget(saved, firstOfMonth);
        }
        return saved;
    }

    @Transactional
    public void deleteTemplate(UUID id, User user) {
        BudgetTemplate template = budgetTemplateRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template non trovato"));
        budgetAlertRepository.deleteByBudgetTemplate(template);
        budgetTemplateRepository.delete(template);
    }

    /**
     * Istanzia i budget dei template attivi per il periodo corrente di ogni ricorrenza (mese, trimestre solare,
     * anno). Gira ogni giorno alle 01:00 e all'avvio dell'applicazione: se il run del primo giorno del periodo è
     * saltato (pod giù, deploy, crash) viene recuperato al primo run utile invece di lasciare l'utente senza
     * budget (e senza alert) per tutto il periodo.
     * <p>
     * Un periodo già istanziato viene marcato in {@link CacheConfig#BUDGET_TEMPLATE_RUNS_CACHE}: i run successivi
     * dello stesso periodo non fanno nulla, così un budget che l'utente cancella a metà periodo non viene ricreato
     * ogni notte. Il claim in {@link CacheConfig#BUDGET_TEMPLATE_RUN_LOCK_CACHE} evita che due pod istanzino lo
     * stesso periodo in parallelo (budget duplicati: non c'è un vincolo univoco su budgets).
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @EventListener(ApplicationReadyEvent.class)
    public void instantiateTemplates() {
        instantiateTemplates(LocalDate.now());
    }

    void instantiateTemplates(LocalDate today) {
        for (RecurrenceType type : RecurrenceType.values()) {
            LocalDate periodStart = periodStart(type, today);
            String key = type + ":" + periodStart;
            Cache runs = cacheManager.getCache(CacheConfig.BUDGET_TEMPLATE_RUNS_CACHE);
            Cache locks = cacheManager.getCache(CacheConfig.BUDGET_TEMPLATE_RUN_LOCK_CACHE);
            try {
                if (runs != null && runs.get(key) != null) {
                    logger.debug("Template budget {} già istanziati per il periodo {}, skip", type, periodStart);
                    continue;
                }
                if (locks != null && locks.putIfAbsent(key, "running") != null) {
                    logger.info("Istanziazione template budget {} per {} già in corso su un'altra istanza, skip", type, periodStart);
                    continue;
                }
                try {
                    transactionTemplate.executeWithoutResult(status -> instantiateForType(type, periodStart, today));
                    if (runs != null) {
                        runs.put(key, "done");
                    }
                } finally {
                    if (locks != null) {
                        locks.evict(key);
                    }
                }
            } catch (Exception e) {
                // Nessun marker: il periodo verrà ritentato al prossimo run (giornaliero o all'avvio)
                logger.error("Istanziazione template budget {} per {} fallita, verrà ritentata: {}",
                        type, periodStart, e.getMessage(), e);
            }
        }
    }

    private void instantiateForType(RecurrenceType type, LocalDate periodStart, LocalDate today) {
        List<BudgetTemplate> templates = budgetTemplateRepository.findByActiveAndRecurrenceType(true, type);

        int created = 0;
        for (BudgetTemplate template : templates) {
            // Evita budget sovrapposti (es. template creato/aggiornato nel periodo, che ha già il suo budget)
            if (budgetRepository.findActiveBudgetByUserAndCategoryAndDate(
                    template.getUser(), template.getCategory(), today).isPresent()) {
                logger.debug("Budget già attivo per template {} alla data {}, skip", template.getId(), today);
                continue;
            }
            createBudgetForPeriod(template, periodStart);
            created++;
            logger.debug("Budget creato da template {} per utente {}", template.getId(), template.getUser().getId());
        }

        logger.info("Istanziati {} budget {} per il periodo che inizia il {}", created, type, periodStart);
    }

    /** Primo giorno del periodo di ricorrenza che contiene {@code date} (trimestri e anni solari). */
    static LocalDate periodStart(RecurrenceType type, LocalDate date) {
        return switch (type) {
            case MONTHLY -> date.withDayOfMonth(1);
            case QUARTERLY -> date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
            case YEARLY -> date.withDayOfYear(1);
        };
    }

    private void upsertCurrentPeriodBudget(BudgetTemplate template, LocalDate startDate) {
        LocalDate endDate = computeEndDate(template.getRecurrenceType(), startDate);
        Optional<Budget> existing = budgetRepository.findActiveBudgetByUserAndCategoryAndDate(
                template.getUser(), template.getCategory(), LocalDate.now());
        if (existing.isPresent()) {
            Budget budget = existing.get();
            budget.setStartDate(startDate);
            budget.setEndDate(endDate);
            budget.setBudgetLimit(template.getBudgetLimit());
            budgetRepository.save(budget);
        } else {
            createBudgetForPeriod(template, startDate);
        }
    }

    private void createBudgetForPeriod(BudgetTemplate template, LocalDate startDate) {
        LocalDate endDate = computeEndDate(template.getRecurrenceType(), startDate);
        Budget budget = Budget.builder()
                .user(template.getUser())
                .category(template.getCategory())
                .budgetLimit(template.getBudgetLimit())
                .startDate(startDate)
                .endDate(endDate)
                .build();
        budgetRepository.save(budget);
    }

    private LocalDate computeEndDate(RecurrenceType type, LocalDate start) {
        return switch (type) {
            case MONTHLY -> start.withDayOfMonth(start.lengthOfMonth());
            // Allineato al trimestre solare: il cron crea i trimestri successivi a gen/apr/lug/ott,
            // un periodo parziale creato a metà trimestre non deve sovrapporsi al successivo.
            case QUARTERLY -> {
                LocalDate quarterEnd = start.withMonth(((start.getMonthValue() - 1) / 3) * 3 + 3);
                yield quarterEnd.withDayOfMonth(quarterEnd.lengthOfMonth());
            }
            case YEARLY -> start.withDayOfYear(start.lengthOfYear());
        };
    }
}
