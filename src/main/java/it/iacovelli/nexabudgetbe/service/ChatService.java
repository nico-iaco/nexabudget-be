package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.ChatDto;
import it.iacovelli.nexabudgetbe.model.ChatMessage;
import it.iacovelli.nexabudgetbe.model.ChatSession;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.ChatMessageRepository;
import it.iacovelli.nexabudgetbe.repository.ChatSessionRepository;
import it.iacovelli.nexabudgetbe.service.chat.FinanceTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_HISTORY_MESSAGES = 20;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            Sei NexaBot, l'assistente finanziario personale di NexaBudget.
            Il tuo ruolo è aiutare l'utente a capire e gestire le proprie finanze in modo chiaro e conciso.

            INFORMAZIONI UTENTE:
            - Valuta di default: %s
            - Data odierna: %s

            LINGUA — REGOLA PRIORITARIA:
            Rileva la lingua del messaggio dell'utente e rispondi SEMPRE nella stessa lingua.
            Se l'utente scrive in italiano, rispondi in italiano. Se scrive in inglese, rispondi in inglese.
            Non cambiare mai lingua a metà risposta.

            ISTRUZIONI:
            - Rispondi con tono professionale ma amichevole.
            - Usa i tool a disposizione per recuperare dati finanziari reali dell'utente prima di rispondere.
            - Sii conciso: preferisci risposte brevi e dirette, usa elenchi puntati se necessario.
            - Non inventare dati: se non hai informazioni recuperate dai tool, dillo chiaramente.
            - Non eseguire mai azioni che modifichino i dati dell'utente (es. creare transazioni, modificare budget).
            - Se l'utente chiede qualcosa fuori dalle tue competenze finanziarie, reindirizzalo gentilmente.

            FORMATO OUTPUT — REGOLA ASSOLUTA:
            - Il tuo output deve contenere ESCLUSIVAMENTE la risposta finale destinata all'utente.
            - È VIETATO includere ragionamenti interni, piani d'azione, auto-valutazioni o commenti su cosa stai per fare.
            - È VIETATO usare frasi in terza persona sull'utente come "The user wants...", "The user is asking...".
            - È VIETATO usare frasi in prima persona meta come "Let me", "I should", "I will", "Now I", "Looking at", "Actually,", "So, the correct answer is:".
            - Inizia DIRETTAMENTE con il contenuto della risposta, senza preamboli.
            """;

    @Value("${nexabudget.ai.chat.model}")
    private String chatModelName;

    private final GoogleGenAiChatModel chatModel;
    private final FinanceTools financeTools;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatDto.ChatResponse chat(User user, ChatDto.ChatRequest request) {
        ChatSession session = resolveOrCreateSession(user, request.sessionId());

        chatMessageRepository.save(ChatMessage.builder()
                .session(session)
                .role("USER")
                .content(request.message())
                .build());

        List<Message> messages = buildPromptMessages(user, session, request.message());

        GoogleGenAiChatOptions.Builder optionsBuilder = GoogleGenAiChatOptions.builder()
                .model(chatModelName)
                .temperature(0.4);
        if (chatModelName.startsWith("gemini-")) {
            optionsBuilder.thinkingLevel(GoogleGenAiThinkingLevel.LOW).includeThoughts(false);
        }
        GoogleGenAiChatOptions options = optionsBuilder.build();
        options.setToolCallbacks(java.util.Arrays.asList(ToolCallbacks.from(financeTools)));

        Prompt prompt = new Prompt(messages, options);

        log.debug("[ChatService] Invocazione Gemini per sessione {}", session.getId());
        var response = chatModel.call(prompt);

        AssistantMessage output = response.getResult().getOutput();
        String replyText = stripThinking(output.getText());

        List<String> toolsUsed = extractToolsUsed(output);

        chatMessageRepository.save(ChatMessage.builder()
                .session(session)
                .role("ASSISTANT")
                .content(replyText)
                .build());

        if (toolsUsed != null && !toolsUsed.isEmpty()) {
            for (String toolName : toolsUsed) {
                chatMessageRepository.save(ChatMessage.builder()
                        .session(session)
                        .role("TOOL")
                        .toolName(toolName)
                        .build());
            }
        }

        if (isFirstExchange(session)) {
            String title = request.message().length() > 60
                    ? request.message().substring(0, 57) + "..."
                    : request.message();
            session.setTitle(title);
        }
        chatSessionRepository.save(session);

        return new ChatDto.ChatResponse(session.getId(), replyText, toolsUsed);
    }

    @Transactional(readOnly = true)
    public List<ChatDto.ChatSessionSummary> listSessions(User user) {
        return chatSessionRepository.findByUserOrderByUpdatedAtDesc(user).stream()
                .map(s -> new ChatDto.ChatSessionSummary(
                        s.getId(),
                        s.getTitle(),
                        s.getUpdatedAt(),
                        chatSessionRepository.countMessagesBySessionId(s.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ChatDto.ChatMessageDto> getMessages(User user, UUID sessionId) {
        ChatSession session = chatSessionRepository.findByIdAndUser(sessionId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessione non trovata"));
        return chatMessageRepository.findBySessionOrderByCreatedAtAsc(session).stream()
                .map(m -> new ChatDto.ChatMessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt(), m.getToolName()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteSession(User user, UUID sessionId) {
        ChatSession session = chatSessionRepository.findByIdAndUser(sessionId, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessione non trovata"));
        chatSessionRepository.delete(session);
    }

    private ChatSession resolveOrCreateSession(User user, UUID sessionId) {
        if (sessionId != null) {
            return chatSessionRepository.findByIdAndUser(sessionId, user)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessione non trovata"));
        }
        ChatSession newSession = ChatSession.builder().user(user).build();
        return chatSessionRepository.save(newSession);
    }

    private List<Message> buildPromptMessages(User user, ChatSession session, String userMessage) {
        String systemText = String.format(SYSTEM_PROMPT_TEMPLATE,
                user.getDefaultCurrency(),
                LocalDate.now());

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemText));

        List<ChatMessage> history = chatMessageRepository.findLastNBySessionId(session.getId(), MAX_HISTORY_MESSAGES);
        // findLastN returns DESC order — reverse for chronological order
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if ("USER".equals(m.getRole()) && m.getContent() != null) {
                messages.add(new UserMessage(m.getContent()));
            } else if ("ASSISTANT".equals(m.getRole()) && m.getContent() != null) {
                messages.add(new AssistantMessage(m.getContent()));
            }
        }

        messages.add(new UserMessage(userMessage));
        return messages;
    }

    private List<String> extractToolsUsed(AssistantMessage output) {
        if (output.getToolCalls() == null || output.getToolCalls().isEmpty()) {
            return List.of();
        }
        return output.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::name)
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean isFirstExchange(ChatSession session) {
        return session.getTitle() == null;
    }

    /**
     * Strips inline thinking blocks that some models (e.g. Gemma 4) emit before the actual reply.
     *
     * Strategy (in order):
     * 1. Remove explicit <think>/<thinking> tags.
     * 2. Find the LAST occurrence of a known transition phrase and return only what follows.
     * 3. If no known pattern is found, return the text unchanged (safe fallback).
     */
    static String stripThinking(String text) {
        if (text == null || text.isBlank()) return text;

        // 1. Explicit <think>/<thinking> tags
        String cleaned = text.replaceAll("(?si)<think(?:ing)?>.*?</think(?:ing)?>\\s*", "");
        if (!cleaned.equals(text)) return cleaned.isBlank() ? text : cleaned.strip();

        // 2. Transition phrases — use the LAST match to avoid cutting real content
        java.util.regex.Pattern transitionPattern = java.util.regex.Pattern.compile(
            "(?im)^(?:so[,.]?\\s*(?:the\\s+)?(?:correct\\s+)?answer\\s+is[:\\s]*" +
            "|i(?:'ll| will)\\s+present\\s+this(?:\\s+clearly)?[:\\s.]*" +
            "|let\\s+me\\s+(?:present|answer|explain|summarize)[:\\s.]*" +
            "|(?:here(?:'s| is))\\s+(?:the\\s+)?(?:answer|risposta|riepilogo)[:\\s]*)$"
        );
        java.util.regex.Matcher m = transitionPattern.matcher(text);
        int lastMatchEnd = -1;
        while (m.find()) {
            lastMatchEnd = m.end();
        }
        if (lastMatchEnd >= 0) {
            String after = text.substring(lastMatchEnd).strip();
            if (!after.isBlank()) return after;
        }

        // 3. No known thinking pattern detected — return unchanged
        return text.strip();
    }
}
