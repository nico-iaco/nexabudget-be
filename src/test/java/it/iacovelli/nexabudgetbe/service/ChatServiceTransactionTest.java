package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.ChatDto;
import it.iacovelli.nexabudgetbe.model.ChatMessage;
import it.iacovelli.nexabudgetbe.model.ChatSession;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.ChatMessageRepository;
import it.iacovelli.nexabudgetbe.repository.ChatSessionRepository;
import it.iacovelli.nexabudgetbe.service.chat.FinanceTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * La chiamata al modello deve avvenire fuori da ogni transazione: lettura dello storico e salvataggio
 * dello scambio sono due transazioni brevi, prima e dopo. Se il modello fallisce non si salva nulla.
 */
class ChatServiceTransactionTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final FinanceTools financeTools = new FinanceTools(
            mock(AccountService.class), mock(TransactionService.class), mock(BudgetService.class),
            mock(CategoryService.class), mock(ReportService.class), mock(CryptoPortfolioService.class),
                mock(InvestmentPortfolioService.class), mock(NetWorthService.class),
            mock(CurrencyConversionService.class), mock(ExchangeRateService.class),
            new com.fasterxml.jackson.databind.ObjectMapper());

    private ChatService chatService;
    private User user;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatClient, financeTools, chatSessionRepository, chatMessageRepository,
                transactionManager);
        user = User.builder().username("chatuser").email("chat@example.com").passwordHash("hash")
                .defaultCurrency("EUR").build();
        user.setId(UUID.randomUUID());

        ReflectionTestUtils.setField(chatService, "chatModelName", "gemini-2.5-flash");
        when(chatSessionRepository.save(any())).thenAnswer(inv -> {
            ChatSession session = inv.getArgument(0);
            if (session.getId() == null) session.setId(UUID.randomUUID());
            return session;
        });
    }

    private void modelReplies(String reply) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(reply)))));
    }

    private void modelUnreachable() {
        when(chatClient.prompt()).thenThrow(new RuntimeException("Gemini down"));
    }

    @Test
    void existingSession_modelCalledBetweenReadAndWriteTransactions() {
        modelReplies("Hai speso 120 EUR.");
        ChatSession session = ChatSession.builder().id(UUID.randomUUID()).user(user).title("Spese").build();
        when(chatSessionRepository.findByIdAndUser(session.getId(), user)).thenReturn(Optional.of(session));
        when(chatMessageRepository.findLastNBySessionId(eq(session.getId()), anyInt())).thenReturn(List.of(
                ChatMessage.builder().session(session).role("USER").content("Domanda precedente").build()));

        ChatDto.ChatResponse response = chatService.chat(user, new ChatDto.ChatRequest(session.getId(), "Quanto ho speso?"));

        InOrder inOrder = inOrder(transactionManager, chatClient);
        inOrder.verify(transactionManager).getTransaction(argThat(TransactionDefinition::isReadOnly));
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(chatClient).prompt();
        inOrder.verify(transactionManager).getTransaction(argThat(def -> !def.isReadOnly()));
        inOrder.verify(transactionManager).commit(any());

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(saved.capture());
        assertEquals("USER", saved.getAllValues().get(0).getRole());
        assertEquals("Quanto ho speso?", saved.getAllValues().get(0).getContent());
        assertEquals("ASSISTANT", saved.getAllValues().get(1).getRole());
        assertEquals("Hai speso 120 EUR.", saved.getAllValues().get(1).getContent());
        assertEquals(session.getId(), response.sessionId());
        assertEquals("Hai speso 120 EUR.", response.reply());
    }

    @Test
    void newSession_noReadTransaction_sessionCreatedAfterModelCall() {
        modelReplies("Ciao!");
        ChatDto.ChatResponse response = chatService.chat(user, new ChatDto.ChatRequest(null, "Ciao"));

        InOrder inOrder = inOrder(chatClient, transactionManager, chatSessionRepository);
        inOrder.verify(chatClient).prompt();
        inOrder.verify(transactionManager).getTransaction(argThat(def -> !def.isReadOnly()));
        inOrder.verify(chatSessionRepository, atLeastOnce()).save(any());
        verify(transactionManager, times(1)).getTransaction(any());
        assertNotNull(response.sessionId());
    }

    @Test
    void modelFailure_existingSession_nothingPersisted() {
        modelUnreachable();
        ChatSession session = ChatSession.builder().id(UUID.randomUUID()).user(user).title("Spese").build();
        when(chatSessionRepository.findByIdAndUser(session.getId(), user)).thenReturn(Optional.of(session));
        when(chatMessageRepository.findLastNBySessionId(eq(session.getId()), anyInt())).thenReturn(List.of());

        ChatDto.ChatResponse response = chatService.chat(user, new ChatDto.ChatRequest(session.getId(), "Quanto ho speso?"));

        verify(chatMessageRepository, never()).save(any());
        verify(chatSessionRepository, never()).save(any());
        verify(transactionManager, never()).getTransaction(argThat(def -> !def.isReadOnly()));
        assertEquals(session.getId(), response.sessionId());
        assertNotNull(response.reply());
    }

    @Test
    void modelFailure_newSession_noSessionCreated() {
        modelUnreachable();

        ChatDto.ChatResponse response = chatService.chat(user, new ChatDto.ChatRequest(null, "Ciao"));

        verify(chatSessionRepository, never()).save(any());
        verify(chatMessageRepository, never()).save(any());
        verifyNoInteractions(transactionManager);
        assertNull(response.sessionId());
    }

    @Test
    void unknownSession_returns404_withoutCallingModel() {
        UUID missing = UUID.randomUUID();
        when(chatSessionRepository.findByIdAndUser(missing, user)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> chatService.chat(user, new ChatDto.ChatRequest(missing, "Ciao")));

        verify(chatClient, never()).prompt();
        verify(chatMessageRepository, never()).save(any());
    }
}
