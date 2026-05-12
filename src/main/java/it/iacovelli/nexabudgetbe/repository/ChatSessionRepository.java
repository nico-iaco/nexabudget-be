package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.ChatSession;
import it.iacovelli.nexabudgetbe.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    List<ChatSession> findByUserOrderByUpdatedAtDesc(User user);

    Optional<ChatSession> findByIdAndUser(UUID id, User user);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.session.id = :sessionId AND m.role IN ('USER', 'ASSISTANT')")
    int countMessagesBySessionId(@Param("sessionId") UUID sessionId);
}
