package it.iacovelli.nexabudgetbe.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Aspect
@Component
public class AuditAspect {

    private static final Logger logger = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─── Transaction ────────────────────────────────────────────────────────────

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.TransactionService.createTransaction(..))",
        returning = "result"
    )
    public void afterCreateTransaction(JoinPoint jp, Object result) {
        audit("CREATE_TRANSACTION", "Transaction", extractIdFromResult(result), result);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.TransactionService.createTransfer(..))",
        returning = "result"
    )
    public void afterCreateTransfer(JoinPoint jp, Object result) {
        audit("CREATE_TRANSFER", "Transaction", null, result);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.TransactionService.updateTransaction(..))",
        returning = "result"
    )
    public void afterUpdateTransaction(JoinPoint jp, Object result) {
        audit("UPDATE_TRANSACTION", "Transaction", extractIdFromResult(result), result);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.TransactionService.deleteTransaction(..))"
    )
    public void afterDeleteTransaction(JoinPoint jp) {
        Object[] args = jp.getArgs();
        String entityId = args.length > 0 ? extractIdFromArg(args[0]) : null;
        audit("DELETE_TRANSACTION", "Transaction", entityId, null);
    }

    // ─── Account ────────────────────────────────────────────────────────────────

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.AccountService.createAccount(..))",
        returning = "result"
    )
    public void afterCreateAccount(JoinPoint jp, Object result) {
        audit("CREATE_ACCOUNT", "Account", extractIdFromResult(result), result);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.AccountService.updateAccount(..))",
        returning = "result"
    )
    public void afterUpdateAccount(JoinPoint jp, Object result) {
        audit("UPDATE_ACCOUNT", "Account", extractIdFromResult(result), result);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.AccountService.deleteAccount(..))"
    )
    public void afterDeleteAccount(JoinPoint jp) {
        Object[] args = jp.getArgs();
        String entityId = args.length > 0 ? String.valueOf(args[0]) : null;
        audit("DELETE_ACCOUNT", "Account", entityId, null);
    }

    // ─── Budget ─────────────────────────────────────────────────────────────────

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.BudgetService.createBudget(..))",
        returning = "result"
    )
    public void afterCreateBudget(JoinPoint jp, Object result) {
        audit("CREATE_BUDGET", "Budget", extractIdFromResult(result), null);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.BudgetService.updateBudget(..))",
        returning = "result"
    )
    public void afterUpdateBudget(JoinPoint jp, Object result) {
        audit("UPDATE_BUDGET", "Budget", extractIdFromResult(result), null);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.BudgetService.deleteBudget(..))"
    )
    public void afterDeleteBudget(JoinPoint jp) {
        Object[] args = jp.getArgs();
        String entityId = args.length > 0 ? String.valueOf(args[0]) : null;
        audit("DELETE_BUDGET", "Budget", entityId, null);
    }

    // ─── Category ───────────────────────────────────────────────────────────────

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.CategoryService.createCategory(..))",
        returning = "result"
    )
    public void afterCreateCategory(JoinPoint jp, Object result) {
        audit("CREATE_CATEGORY", "Category", extractIdFromResult(result), null);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.CategoryService.updateCategory(..))",
        returning = "result"
    )
    public void afterUpdateCategory(JoinPoint jp, Object result) {
        audit("UPDATE_CATEGORY", "Category", extractIdFromResult(result), null);
    }

    @AfterReturning(
        pointcut = "execution(* it.iacovelli.nexabudgetbe.service.CategoryService.deleteCategory(..))"
    )
    public void afterDeleteCategory(JoinPoint jp) {
        Object[] args = jp.getArgs();
        String entityId = args.length > 0 ? String.valueOf(args[0]) : null;
        audit("DELETE_CATEGORY", "Category", entityId, null);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private void audit(String action, String entityType, String entityId, Object payload) {
        try {
            UUID userId = resolveUserId();
            String ipAddress = resolveIpAddress();
            String newValue = payload != null ? serialize(payload) : null;
            auditLogService.record(userId, action, entityType, entityId, newValue, ipAddress);
        } catch (Exception e) {
            logger.warn("Audit log fallito per {}/{}: {}", action, entityType, e.getMessage());
        }
    }

    private UUID resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    private String resolveIpAddress() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the entity ID from a known service return type.
     * Uses pattern-matching switch instead of dynamic reflection so that
     * GraalVM native-image static analysis can detect all accessed methods.
     */
    private String extractIdFromResult(Object result) {
        return switch (result) {
            case null -> null;
            case TransactionDto.TransactionResponse r -> r.getId() != null ? r.getId().toString() : null;
            case AccountDto.AccountResponse r -> r.getId() != null ? r.getId().toString() : null;
            case Budget b -> b.getId() != null ? b.getId().toString() : null;
            case Category c -> c.getId() != null ? c.getId().toString() : null;
            default -> null;
        };
    }

    /**
     * Extracts an entity ID from a known service method argument.
     * Uses pattern-matching switch — no dynamic reflection.
     */
    private String extractIdFromArg(Object arg) {
        return switch (arg) {
            case null -> null;
            case UUID id -> id.toString();
            case Transaction t -> t.getId() != null ? t.getId().toString() : null;
            default -> null;
        };
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
