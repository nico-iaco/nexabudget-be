package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetAlertEmailContext;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

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

        MimeMessage message = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        // When
        emailService.sendBudgetAlertEmail(context);

        // Then
        verify(mailSender, times(1)).createMimeMessage();
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void sendAiReportEmail_ShouldAttachPdfAndSendEmail() throws Exception {
        MimeMessage message = new MimeMessage(Session.getDefaultInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        byte[] pdfBytes = "PDF".getBytes(StandardCharsets.UTF_8);

        boolean result = emailService.sendAiReportEmail(
                "test@example.com",
                "testuser",
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 31),
                pdfBytes,
                "report.pdf"
        );

        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));

        Object content = message.getContent();
        assertTrue(content instanceof Multipart);
        Multipart multipart = (Multipart) content;

        boolean hasPdf = false;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String filename = part.getFileName();
            String contentType = part.getContentType();
            if ((filename != null && filename.contains("report.pdf"))
                    || (contentType != null && contentType.toLowerCase().contains("application/pdf"))) {
                hasPdf = true;
                break;
            }
        }

        assertTrue(hasPdf);
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
