package it.iacovelli.nexabudgetbe.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public interface TrashTransactionView {
    UUID getId();
    UUID getAccountId();
    String getAccountName();
    UUID getCategoryId();
    String getCategoryName();
    BigDecimal getAmount();
    String getType();
    String getDescription();
    LocalDate getDate();
    String getNote();
    String getTransferId();
    BigDecimal getExchangeRate();
    String getOriginalCurrency();
    BigDecimal getOriginalAmount();
    LocalDateTime getDeletedAt();
}
