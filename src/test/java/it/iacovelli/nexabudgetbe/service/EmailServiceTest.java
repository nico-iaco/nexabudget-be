package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MimeMessage mimeMessage;

    @InjectMocks
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailService, "fromEmail", "noreply@nexabudget.it");
    }

    @Test
    void sendBudgetAlertEmail_ShouldSendEmail() {
        // Given
        User user = User.builder()
                .username("testuser")
                .email("test@example.com")
                .defaultCurrency("EUR")
                .build();

        Category category = Category.builder()
                .name("Alimentari")
                .build();

        Budget budget = Budget.builder()
                .category(category)
                .budgetLimit(new BigDecimal("500.00"))
                .startDate(LocalDate.now().withDayOfMonth(1))
                .endDate(LocalDate.now().withDayOfMonth(28))
                .build();

        BudgetAlert alert = BudgetAlert.builder()
                .thresholdPercentage(80)
                .build();

        BigDecimal usagePercent = new BigDecimal("85.5");

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailService.sendBudgetAlertEmail(user, alert, budget, usagePercent);

        // Then
        verify(mailSender, times(1)).createMimeMessage();
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void sendBudgetAlertEmail_WhenMessagingException_ShouldLogAndNotThrow() {
        // Given
        User user = User.builder().email("test@example.com").build();
        Category category = Category.builder().name("Test").build();
        Budget budget = Budget.builder().category(category).budgetLimit(BigDecimal.TEN).startDate(LocalDate.now()).build();
        BudgetAlert alert = BudgetAlert.builder().thresholdPercentage(50).build();
        
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Mail server down"));

        // When & Then
        emailService.sendBudgetAlertEmail(user, alert, budget, BigDecimal.valueOf(60));
        
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
