package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetAlertEmailContext;
import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.BudgetTemplate;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.BudgetAlertRepository;
import it.iacovelli.nexabudgetbe.repository.BudgetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BudgetAlertService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetAlertService.class);

    private final BudgetAlertRepository budgetAlertRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetService budgetService;
    private final EmailService emailService;
    private final TransactionTemplate transactionTemplate;

    /** Alert con un'email in volo (per-pod): non vanno rivalutati finché l'esito non è noto. */
    private final Set<UUID> sendsInFlight = ConcurrentHashMap.newKeySet();

    public BudgetAlertService(BudgetAlertRepository budgetAlertRepository,
                               BudgetRepository budgetRepository,
                               BudgetService budgetService,
                               EmailService emailService,
                               PlatformTransactionManager transactionManager) {
        this.budgetAlertRepository = budgetAlertRepository;
        this.budgetRepository = budgetRepository;
        this.budgetService = budgetService;
        this.emailService = emailService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    public List<BudgetAlert> getAlertsByUserAndTemplate(User user, UUID templateId) {
        return budgetAlertRepository.findByUserAndBudgetTemplateId(user, templateId);
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
     * Controlla ogni ora gli alert attivi: per ciascuno cerca il budget attivo della coppia utente+categoria del
     * template e confronta lo speso con la soglia.
     * <p>
     * Niente transazione unica sul job: ogni alert è valutato in una transazione breve propria (un alert che
     * fallisce non blocca né annulla gli altri) e l'email parte in modo asincrono fuori da ogni transazione.
     * {@code lastNotifiedAt} viene scritto con un update mirato solo a invio riuscito; finché l'invio è in corso
     * l'alert non viene rivalutato (evita doppie email se l'SMTP è lento).
     */
    @Scheduled(fixedRate = 3_600_000)
    public void checkAlerts() {
        LocalDate today = LocalDate.now();
        logger.info("[BudgetAlert] Avvio controllo alert budget - data odierna: {}", today);

        List<UUID> activeAlertIds = budgetAlertRepository.findActiveIds();
        logger.info("[BudgetAlert] Alert attivi trovati: {}", activeAlertIds.size());

        for (UUID alertId : activeAlertIds) {
            if (sendsInFlight.contains(alertId)) {
                logger.info("[BudgetAlert] Alert {}: invio email precedente ancora in corso, skip", alertId);
                continue;
            }
            try {
                Optional<BudgetAlertEmailContext> toSend = transactionTemplate.execute(status -> evaluateAlert(alertId, today));
                if (toSend != null && toSend.isPresent()) {
                    dispatchEmail(alertId, toSend.get());
                }
            } catch (Exception e) {
                logger.error("[BudgetAlert] Alert {}: errore durante la valutazione, proseguo con gli altri: {}",
                        alertId, e.getMessage(), e);
            }
        }

        logger.info("[BudgetAlert] Controllo alert completato");
    }

    /**
     * Valuta un alert (dentro una transazione breve). Restituisce il contesto dell'email da inviare se la soglia è
     * superata e l'alert non è già stato consumato nel periodo; ri-arma l'alert se l'utilizzo è rientrato sotto soglia.
     */
    private Optional<BudgetAlertEmailContext> evaluateAlert(UUID alertId, LocalDate today) {
        BudgetAlert alert = budgetAlertRepository.findById(alertId).orElse(null);
        if (alert == null || !Boolean.TRUE.equals(alert.getActive())) {
            return Optional.empty();
        }
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
            return Optional.empty();
        }

        Budget budget = budgetOpt.get();

        if (budget.getBudgetLimit().compareTo(BigDecimal.ZERO) == 0) {
            logger.warn("[BudgetAlert] Alert {}: limite budget è 0 per categoria='{}', skip", alert.getId(), categoryName);
            return Optional.empty();
        }

        BigDecimal spent = budgetService.getSpentInBudgetPeriod(budget, today);

        double usagePercent = spent.divide(budget.getBudgetLimit(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        logger.info("[BudgetAlert] Alert {}: utente={}, categoria='{}', utilizzo={}% (soglia {}%)",
                alert.getId(), userEmail, categoryName,
                String.format("%.1f", usagePercent), alert.getThresholdPercentage());

        if (usagePercent < alert.getThresholdPercentage()) {
            if (alert.getLastNotifiedAt() != null) {
                logger.info("[BudgetAlert] Alert {}: utilizzo rientrato sotto soglia ({}% < {}%), alert ri-armato per il periodo corrente",
                        alert.getId(), String.format("%.1f", usagePercent), alert.getThresholdPercentage());
                budgetAlertRepository.updateLastNotifiedAt(alert.getId(), null);
            } else {
                logger.debug("[BudgetAlert] Alert {}: utilizzo sotto soglia, nessuna notifica", alert.getId());
            }
            return Optional.empty();
        }

        boolean alreadyNotifiedThisPeriod = alert.getLastNotifiedAt() != null &&
                !alert.getLastNotifiedAt().isBefore(budget.getStartDate().atStartOfDay());
        if (alreadyNotifiedThisPeriod) {
            logger.info("[BudgetAlert] Alert {}: soglia superata ma già notificato per questo periodo (ultima notifica: {})",
                    alert.getId(), alert.getLastNotifiedAt());
            return Optional.empty();
        }

        logger.warn("[BudgetAlert] SOGLIA SUPERATA - invio notifica email a {} per categoria='{}' ({}% >= {}%)",
                userEmail, categoryName,
                String.format("%.1f", usagePercent), alert.getThresholdPercentage());

        // DTO costruito qui: il thread asincrono dell'email non ha una sessione Hibernate
        return Optional.of(BudgetAlertEmailContext.builder()
                .userEmail(userEmail)
                .username(template.getUser().getUsername())
                .categoryName(categoryName)
                .budgetLimit(budget.getBudgetLimit())
                .currency(template.getUser().getDefaultCurrency())
                .startDate(budget.getStartDate())
                .endDate(budget.getEndDate())
                .thresholdPercentage(alert.getThresholdPercentage())
                .usagePercent(BigDecimal.valueOf(usagePercent))
                .build());
    }

    private void dispatchEmail(UUID alertId, BudgetAlertEmailContext context) {
        sendsInFlight.add(alertId);
        emailService.sendBudgetAlertEmailAsync(context).whenComplete((sent, error) -> {
            try {
                if (Boolean.TRUE.equals(sent)) {
                    budgetAlertRepository.updateLastNotifiedAt(alertId, LocalDateTime.now());
                } else {
                    logger.warn("[BudgetAlert] Alert {}: invio email fallito, lastNotifiedAt non aggiornato — verrà ritentato al prossimo check",
                            alertId);
                }
            } catch (Exception e) {
                logger.error("[BudgetAlert] Alert {}: email inviata ma aggiornamento lastNotifiedAt fallito: {}",
                        alertId, e.getMessage(), e);
            } finally {
                sendsInFlight.remove(alertId);
            }
        });
    }
}
