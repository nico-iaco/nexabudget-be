package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Budget;
import it.iacovelli.nexabudgetbe.model.BudgetAlert;
import it.iacovelli.nexabudgetbe.model.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@nexabudget.it}")
    private String fromEmail;

    @Async
    public void sendBudgetAlertEmail(User user, BudgetAlert alert, Budget budget, BigDecimal usagePercent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(user.getEmail());
            helper.setSubject("⚠️ Avviso Budget: Hai superato la soglia per " + budget.getCategory().getName());

            String htmlContent = generateBudgetAlertHtml(user, alert, budget, usagePercent);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Email di avviso budget inviata con successo a {} per la categoria {}", user.getEmail(), budget.getCategory().getName());
        } catch (MessagingException e) {
            log.error("Errore durante l'invio dell'email di avviso budget a {}: {}", user.getEmail(), e.getMessage());
        } catch (Exception e) {
            log.error("Errore imprevisto durante l'invio dell'email: {}", e.getMessage());
        }
    }

    private String generateBudgetAlertHtml(User user, BudgetAlert alert, Budget budget, BigDecimal usagePercent) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String period = budget.getEndDate() != null ? 
            String.format("dal %s al %s", budget.getStartDate().format(formatter), budget.getEndDate().format(formatter)) :
            String.format("a partire dal %s", budget.getStartDate().format(formatter));

        String currency = user.getDefaultCurrency();
        
        String escapedUsername = HtmlUtils.htmlEscape(user.getUsername());
        String escapedCategoryName = HtmlUtils.htmlEscape(budget.getCategory().getName());
        
        return String.format("""
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333 text-align: left;">
                <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #eee; border-radius: 10px;">
                    <h2 style="color: #d9534f;">⚠️ Avviso Superamento Budget</h2>
                    <p>Ciao <strong>%s</strong>,</p>
                    <p>Ti informiamo che hai superato la soglia di avviso configurata per il tuo budget.</p>
                    
                    <div style="background-color: #f9f9f9; padding: 15px; border-radius: 5px; margin: 20px 0;">
                        <p style="margin: 5px 0;"><strong>Categoria:</strong> %s</p>
                        <p style="margin: 5px 0;"><strong>Periodo:</strong> %s</p>
                        <p style="margin: 5px 0;"><strong>Soglia di avviso:</strong> %d%%</p>
                        <p style="margin: 5px 0; font-size: 18px; color: #d9534f;"><strong>Utilizzo attuale: %s%%</strong></p>
                    </div>
                    
                    <table style="width: 100%%; border-collapse: collapse;">
                        <tr>
                            <td style="padding: 10px; border-bottom: 1px solid #eee;"><strong>Limite Budget:</strong></td>
                            <td style="padding: 10px; border-bottom: 1px solid #eee; text-align: right;">%s %s</td>
                        </tr>
                    </table>
                    
                    <p style="margin-top: 25px;">Accedi all'app per vedere i dettagli e gestire le tue transazioni.</p>
                    
                    <hr style="border: 0; border-top: 1px solid #eee; margin: 20px 0;">
                    <p style="font-size: 12px; color: #777;">Questa è una notifica automatica da nexaBudget. Non rispondere a questa email.</p>
                </div>
            </body>
            </html>
            """, 
            escapedUsername,
            escapedCategoryName,
            period,
            alert.getThresholdPercentage(),
            usagePercent.setScale(1, RoundingMode.HALF_UP).toString(),
            budget.getBudgetLimit().setScale(2, RoundingMode.HALF_UP).toString(),
            currency
        );
    }
}
