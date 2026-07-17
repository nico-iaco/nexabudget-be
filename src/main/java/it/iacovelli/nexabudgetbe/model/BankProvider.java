package it.iacovelli.nexabudgetbe.model;

/**
 * Provider di aggregazione bancaria (Open Banking/PSD2) collegato a un {@link Account}.
 * Un account senza provider (null) è un conto gestito manualmente, non sincronizzato.
 */
public enum BankProvider {
    GOCARDLESS,
    ENABLE_BANKING
}
