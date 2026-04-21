package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.repository.BudgetAlertRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public BudgetTemplateService(BudgetTemplateRepository budgetTemplateRepository,
                                  BudgetRepository budgetRepository,
                                  BudgetAlertRepository budgetAlertRepository) {
        this.budgetTemplateRepository = budgetTemplateRepository;
        this.budgetRepository = budgetRepository;
        this.budgetAlertRepository = budgetAlertRepository;
    }

    @Transactional
    public BudgetTemplate createTemplate(BudgetTemplate template) {
        BudgetTemplate saved = budgetTemplateRepository.save(template);
        if (Boolean.TRUE.equals(saved.getActive())) {
            createBudgetForPeriod(saved, LocalDate.now());
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
        return budgetTemplateRepository.save(template);
    }

    @Transactional
    public void deleteTemplate(UUID id, User user) {
        BudgetTemplate template = budgetTemplateRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template non trovato"));
        budgetAlertRepository.deleteByBudgetTemplate(template);
        budgetTemplateRepository.delete(template);
    }

    /**
     * Runs at 01:00 on the 1st of every month.
     * Creates Budget instances for active templates whose recurrence matches.
     */
    @Scheduled(cron = "0 0 1 1 * ?")
    @Transactional
    public void instantiateTemplates() {
        LocalDate today = LocalDate.now();
        logger.info("Avvio istanziazione template budget per {}", today);

        instantiateForType(RecurrenceType.MONTHLY, today);

        int month = today.getMonthValue();
        if (month == 1 || month == 4 || month == 7 || month == 10) {
            instantiateForType(RecurrenceType.QUARTERLY, today);
        }

        if (month == 1) {
            instantiateForType(RecurrenceType.YEARLY, today);
        }
    }

    private void instantiateForType(RecurrenceType type, LocalDate today) {
        List<BudgetTemplate> templates = budgetTemplateRepository.findByActiveAndRecurrenceType(true, type);

        for (BudgetTemplate template : templates) {
            createBudgetForPeriod(template, today);
            logger.debug("Budget creato da template {} per utente {}", template.getId(), template.getUser().getId());
        }

        logger.info("Istanziati {} budget {} per {}", templates.size(), type, today);
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
            case QUARTERLY -> start.plusMonths(2).withDayOfMonth(start.plusMonths(2).lengthOfMonth());
            case YEARLY -> start.withDayOfYear(start.lengthOfYear());
        };
    }
}
