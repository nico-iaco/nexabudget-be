package it.iacovelli.nexabudgetbe.dto.bank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Rappresentazione normalizzata di una banca/istituzione supportata, indipendente dal provider
 * (GoCardless institution, Enable Banking ASPSP, ...).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankInstitutionDto {
    private String id;
    private String name;
    private String bic;
    private List<String> countries;
    private String logo;
    private int maxAccessValidForDays;
}
