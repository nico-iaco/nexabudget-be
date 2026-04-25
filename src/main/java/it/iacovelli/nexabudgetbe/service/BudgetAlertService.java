package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetAlertEmailContext;
import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.BudgetAlertRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BudgetAlertService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetAlertService.class);

    private final BudgetAlertRepository budgetAlertRepository;
    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final EmailService emailService;

    public BudgetAlertService(BudgetAlertRepository budgetAlertRepository,
                               BudgetRepository budgetRepository,
                               TransactionRepository transactionRepository,
                               EmailService emailService) {
        this.budgetAlertRepository = budgetAlertRepository;
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
        this.emailService = emailService;
    }

    @Transactional
    public BudgetAlert createAlert(BudgetAlert alert) {
        return budgetAlertRepository.save(alert);
    }

    @Transactional(readOnly = true)
    public List<BudgetAlert> getAlertsByUser(User user) {
        return budgetAlertRepository.findByUser(user);
    }

    @Transactional(readOnly = true)
    public Optional<BudgetAlert> getAlertByIdAndUser(UUID id, User user) {
        return budgetAlertRepository.findByIdAndUser(id, user);
    }

    @Transactional
    public BudgetAlert updateAlert(BudgetAlert alert) {
        return budgetAlertRepository.save(alert);
    }

    @Transactional
    public void deleteAlert(UUID id, User user) {
        BudgetAlert alert = budgetAlertRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert non trovato"));
        budgetAlertRepository.delete(alert);
    }

    @Transactional
    public void deleteAlertsByBudgetTemplate(BudgetTemplate template) {
        budgetAlertRepository.deleteByBudgetTemplate(template);
    }

    /**
     * Checks active alerts every hour.
     * For each alert, finds the currently active Budget for the linked template's user+category
     * and compares spending against the threshold.
     */
    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void checkAlerts() {
        LocalDate today = LocalDate.now();
        logger.info("[BudgetAlert] Avvio controllo alert budget - data odierna: {}", today);

        List<BudgetAlert> activeAlerts = budgetAlertRepository.findByActive(true);
        logger.info("[BudgetAlert] Alert attivi trovati: {}", activeAlerts.size());

        for (BudgetAlert alert : activeAlerts) {
            BudgetTemplate template = alert.getBudgetTemplate();
            String categoryName = template.getCategory().getName();
            String userEmail = template.getUser().getEmail();

            logger.debug("[BudgetAlert] Controllo alert {} - utente={}, categoria='{}', soglia={}%",
                    alert.getId(), userEmail, categoryName, alert.getThresholdPercentage());

            Optional<Budget> budgetOpt = budgetRepository.findActiveBudgetByUserAndCategoryAndDate(
                    template.getUser(), template.getCategory(), today);

            if (budgetOpt.isEmpty()) {
                logger.info("[BudgetAlert] Alert {}: nessun budget attivo trovato per utente={}, categoria='{}'",
                        alert.getId(), userEmail, categoryName);
                continue;
            }

            Budget budget = budgetOpt.get();

            BigDecimal spent = transactionRepository.sumOutByUserAndCategoryAndDateRange(
                    budget.getUser(), budget.getCategory(), budget.getStartDate(),
                    budget.getEndDate() != null ? budget.getEndDate() : today);
            if (spent == null) spent = BigDecimal.ZERO;

            if (budget.getBudgetLimit().compareTo(BigDecimal.ZERO) == 0) {
                logger.warn("[BudgetAlert] Alert {}: limite budget è 0 per categoria='{}', skip", alert.getId(), categoryName);
                continue;
            }

            double usagePercent = spent.divide(budget.getBudgetLimit(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();

            logger.info("[BudgetAlert] Alert {}: utente={}, categoria='{}', utilizzo={}% (soglia {}%)",
                    alert.getId(), userEmail, categoryName,
                    String.format("%.1f", usagePercent), alert.getThresholdPercentage());

            if (usagePercent >= alert.getThresholdPercentage()) {
                boolean alreadyNotifiedThisPeriod = alert.getLastNotifiedAt() != null &&
                        !alert.getLastNotifiedAt().isBefore(budget.getStartDate().atStartOfDay());
                if (!alreadyNotifiedThisPeriod) {

                    logger.warn("[BudgetAlert] SOGLIA SUPERATA - invio notifica email a {} per categoria='{}' ({}% >= {}%)",
                            userEmail, categoryName,
                            String.format("%.1f", usagePercent), alert.getThresholdPercentage());

                    // Create DTO to avoid LazyInitializationException in Async thread
                    BudgetAlertEmailContext emailContext = BudgetAlertEmailContext.builder()
                            .userEmail(userEmail)
                            .username(template.getUser().getUsername())
                            .categoryName(categoryName)
                            .budgetLimit(budget.getBudgetLimit())
                            .currency(template.getUser().getDefaultCurrency())
                            .startDate(budget.getStartDate())
                            .endDate(budget.getEndDate())
                            .thresholdPercentage(alert.getThresholdPercentage())
                            .usagePercent(BigDecimal.valueOf(usagePercent))
                            .build();

                    boolean sent = emailService.sendBudgetAlertEmail(emailContext);
                    if (sent) {
                        alert.setLastNotifiedAt(LocalDateTime.now());
                        budgetAlertRepository.save(alert);
                    } else {
                        logger.warn("[BudgetAlert] Alert {}: invio email fallito, lastNotifiedAt non aggiornato — verrà ritentato al prossimo check",
                                alert.getId());
                    }
                } else {
                    logger.info("[BudgetAlert] Alert {}: soglia superata ma già notificato per questo periodo (ultima notifica: {})",
                            alert.getId(), alert.getLastNotifiedAt());
                }
            } else {
                logger.debug("[BudgetAlert] Alert {}: utilizzo sotto soglia, nessuna notifica", alert.getId());
            }
        }

        logger.info("[BudgetAlert] Controllo alert completato");
    }
}
