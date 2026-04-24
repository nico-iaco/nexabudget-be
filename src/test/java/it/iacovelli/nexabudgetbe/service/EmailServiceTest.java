package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetAlertEmailContext;
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
        BudgetAlertEmailContext context = BudgetAlertEmailContext.builder()
                .userEmail("test@example.com")
                .username("testuser")
                .categoryName("Alimentari")
                .budgetLimit(new BigDecimal("500.00"))
                .currency("EUR")
                .startDate(LocalDate.now().withDayOfMonth(1))
                .endDate(LocalDate.now().withDayOfMonth(28))
                .thresholdPercentage(80)
                .usagePercent(new BigDecimal("85.5"))
                .build();

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        // When
        emailService.sendBudgetAlertEmail(context);

        // Then
        verify(mailSender, times(1)).createMimeMessage();
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void sendBudgetAlertEmail_WhenMailSenderFails_ShouldLogAndNotThrow() {
        // Given
        BudgetAlertEmailContext context = BudgetAlertEmailContext.builder()
                .userEmail("test@example.com")
                .username("testuser")
                .categoryName("Test")
                .budgetLimit(BigDecimal.TEN)
                .startDate(LocalDate.now())
                .thresholdPercentage(50)
                .usagePercent(BigDecimal.valueOf(60))
                .build();
        
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Mail server down"));

        // When & Then
        emailService.sendBudgetAlertEmail(context);
        
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
