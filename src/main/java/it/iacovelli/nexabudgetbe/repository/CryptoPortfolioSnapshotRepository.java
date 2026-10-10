package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.CryptoPortfolioSnapshot;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CryptoPortfolioSnapshotRepository extends JpaRepository<CryptoPortfolioSnapshot, UUID> {

    Optional<CryptoPortfolioSnapshot> findByUserAndSnapshotDate(User user, LocalDate snapshotDate);

    List<CryptoPortfolioSnapshot> findByUserAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            User user, LocalDate start, LocalDate end);
}
