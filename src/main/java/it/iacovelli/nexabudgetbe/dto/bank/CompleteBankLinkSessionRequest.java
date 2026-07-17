package it.iacovelli.nexabudgetbe.dto.bank;

import lombok.Data;

/**
 * Body di POST /api/banking/{provider}/{localAccountId}/session — scambio del {@code code}
 * ricevuto in callback (Enable Banking) con una sessione. Per GoCardless questo step è un no-op
 * lato provider (il completamento avviene per polling), ma l'endpoint resta uniforme.
 */
@Data
public class CompleteBankLinkSessionRequest {
    private String code;
}
