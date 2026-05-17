package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.model.UserCoinbaseKeys;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserCoinbaseKeysRepository extends JpaRepository<UserCoinbaseKeys, UUID> {
    Optional<UserCoinbaseKeys> findByUser(User user);
}
