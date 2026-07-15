package it.iacovelli.nexabudgetbe.dto.enablebanking;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Riferimento minimale a un ASPSP {name, country} usato in POST /auth. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EnableBankingAspspRef {
    private String name;
    private String country;
}
