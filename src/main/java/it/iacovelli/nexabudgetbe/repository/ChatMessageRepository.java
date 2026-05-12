package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.ChatMessage;
import it.iacovelli.nexabudgetbe.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findBySessionOrderByCreatedAtAsc(ChatSession session);

    @Query("SELECT m FROM ChatMessage m WHERE m.session.id = :sessionId ORDER BY m.createdAt DESC LIMIT :limit")
    List<ChatMessage> findLastNBySessionId(@Param("sessionId") UUID sessionId, @Param("limit") int limit);
}
