package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.BudgetAlertEmailContext;
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

    public boolean sendBudgetAlertEmail(BudgetAlertEmailContext context) {
        log.info("[EmailService] Tentativo invio email budget alert a {} per categoria '{}'",
                context.getUserEmail(), context.getCategoryName());
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(context.getUserEmail());

            String sanitizedCategory = context.getCategoryName()
                .replaceAll("[\\r\\n]+", " ")
                .trim();
            if (sanitizedCategory.length() > 50) {
                sanitizedCategory = sanitizedCategory.substring(0, 47) + "...";
            }

            helper.setSubject("⚠️ Avviso Budget: Hai superato la soglia per " + sanitizedCategory);

            String htmlContent = generateBudgetAlertHtml(context);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("[EmailService] Email inviata con successo a {} per la categoria '{}'",
                    context.getUserEmail(), context.getCategoryName());
            return true;
        } catch (MessagingException e) {
            log.error("[EmailService] Errore MessagingException per {}: {}", context.getUserEmail(), e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("[EmailService] Errore imprevisto per {}: {}", context.getUserEmail(), e.getMessage(), e);
            return false;
        }
    }

    private String generateBudgetAlertHtml(BudgetAlertEmailContext context) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String period = context.getEndDate() != null ? 
            String.format("dal %s al %s", context.getStartDate().format(formatter), context.getEndDate().format(formatter)) :
            String.format("a partire dal %s", context.getStartDate().format(formatter));

        String currency = context.getCurrency();
        
        String escapedUsername = HtmlUtils.htmlEscape(context.getUsername());
        String escapedCategoryName = HtmlUtils.htmlEscape(context.getCategoryName());
        String escapedCurrency = HtmlUtils.htmlEscape(currency);
        
        return String.format("""
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; text-align: left;">
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
            context.getThresholdPercentage(),
            context.getUsagePercent().setScale(1, RoundingMode.HALF_UP).toString(),
            context.getBudgetLimit().setScale(2, RoundingMode.HALF_UP).toString(),
            escapedCurrency
        );
    }
}
