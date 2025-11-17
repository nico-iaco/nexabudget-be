package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.model.UserBinanceKeys;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserBinanceKeysRepository extends JpaRepository<UserBinanceKeys, UUID> {
    Optional<UserBinanceKeys> findByUser(User user);
}
