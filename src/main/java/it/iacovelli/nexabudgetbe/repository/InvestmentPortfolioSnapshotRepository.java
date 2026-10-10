package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.InvestmentPortfolioSnapshot;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentPortfolioSnapshotRepository extends JpaRepository<InvestmentPortfolioSnapshot, UUID> {

    Optional<InvestmentPortfolioSnapshot> findByUserAndSnapshotDate(User user, LocalDate snapshotDate);

    List<InvestmentPortfolioSnapshot> findByUserAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            User user, LocalDate start, LocalDate end);

    // Ultimo snapshot disponibile fino a una data (per il valore "a inizio periodo")
    Optional<InvestmentPortfolioSnapshot> findFirstByUserAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
            User user, LocalDate date);
}
