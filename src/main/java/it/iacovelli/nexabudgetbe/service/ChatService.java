package it.iacovelli.nexabudgetbe.service;

import com.google.genai.Models;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Tool;
import it.iacovelli.nexabudgetbe.dto.ChatDto;
import it.iacovelli.nexabudgetbe.model.ChatMessage;
import it.iacovelli.nexabudgetbe.model.ChatSession;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.ChatMessageRepository;
import it.iacovelli.nexabudgetbe.repository.ChatSessionRepository;
import it.iacovelli.nexabudgetbe.service.chat.FinanceTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_TOOL_ITERATIONS = 10;

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

    @Value("${nexabudget.ai.chat.thinking-budget}")
    private int thinkingBudget;

    @Value("${nexabudget.ai.chat.thinking-level}")
    private String thinkingLevel;

    private final Models genaiModels;
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

        String systemText = String.format(SYSTEM_PROMPT_TEMPLATE,
                user.getDefaultCurrency(),
                LocalDate.now());

        Content systemInstruction = Content.builder()
                .parts(List.of(Part.fromText(systemText)))
                .build();

        List<Content> contents = buildContents(session, request.message());

        GenerateContentConfig.Builder cfgBuilder = GenerateContentConfig.builder()
                .temperature(0.4f)
                .systemInstruction(systemInstruction)
                .tools(List.of(buildFinanceTool()));

        if (supportsThinking(chatModelName)) {
            cfgBuilder.thinkingConfig(buildThinkingConfig(chatModelName, thinkingBudget, thinkingLevel));
        }

        GenerateContentConfig cfg = cfgBuilder.build();

        log.debug("[ChatService] Invocazione modello {} per sessione {}", chatModelName, session.getId());

        ChatResult result = callWithFunctionLoop(contents, cfg);

        chatMessageRepository.save(ChatMessage.builder()
                .session(session)
                .role("ASSISTANT")
                .content(result.replyText())
                .build());

        for (String toolName : result.toolsUsed()) {
            chatMessageRepository.save(ChatMessage.builder()
                    .session(session)
                    .role("TOOL")
                    .toolName(toolName)
                    .build());
        }

        if (isFirstExchange(session)) {
            String title = request.message().length() > 60
                    ? request.message().substring(0, 57) + "..."
                    : request.message();
            session.setTitle(title);
        }
        chatSessionRepository.save(session);

        return new ChatDto.ChatResponse(session.getId(), result.replyText(), result.toolsUsed());
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

    private List<Content> buildContents(ChatSession session, String userMessage) {
        List<Content> contents = new ArrayList<>();
        List<ChatMessage> history = chatMessageRepository.findLastNBySessionId(session.getId(), MAX_HISTORY_MESSAGES);
        // findLastN returns DESC — reverse for chronological order
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if ("USER".equals(m.getRole()) && m.getContent() != null) {
                contents.add(Content.builder().role("user").parts(List.of(Part.fromText(m.getContent()))).build());
            } else if ("ASSISTANT".equals(m.getRole()) && m.getContent() != null) {
                contents.add(Content.builder().role("model").parts(List.of(Part.fromText(m.getContent()))).build());
            }
        }
        contents.add(Content.builder().role("user").parts(List.of(Part.fromText(userMessage))).build());
        return contents;
    }

    private ChatResult callWithFunctionLoop(List<Content> contents, GenerateContentConfig cfg) {
        List<String> toolsUsed = new ArrayList<>();
        for (int iter = 0; iter < MAX_TOOL_ITERATIONS; iter++) {
            GenerateContentResponse resp;
            try {
                resp = genaiModels.generateContent(chatModelName, contents, cfg);
            } catch (ApiException e) {
                log.error("[ChatService] Errore API Gemini ({}): {}", e.code(), e.getMessage());
                return new ChatResult("Si è verificato un errore nella comunicazione con l'AI. Riprova tra poco.", toolsUsed);
            }

            List<FunctionCall> calls = resp.functionCalls();
            if (calls == null || calls.isEmpty()) {
                return new ChatResult(stripThinking(resp.text()), toolsUsed);
            }

            // collect the model's function-call content for the conversation history
            resp.candidates()
                    .flatMap(cs -> cs.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(cs.get(0)))
                    .flatMap(c -> c.content())
                    .ifPresent(contents::add);

            // execute each tool and build function response parts
            List<Part> responseParts = new ArrayList<>();
            for (FunctionCall fc : calls) {
                String name = fc.name().orElse("unknown");
                Map<String, Object> args = fc.args().orElse(Map.of());
                toolsUsed.add(name);
                log.debug("[ChatService] Tool invocato: {} con args: {}", name, args);
                String toolResult = dispatchTool(name, args);
                responseParts.add(Part.fromFunctionResponse(name, Map.of("result", toolResult)));
            }

            contents.add(Content.builder().role("user").parts(responseParts).build());
        }
        log.warn("[ChatService] Raggiunto il limite di {} iterazioni tool calling", MAX_TOOL_ITERATIONS);
        return new ChatResult("Non sono riuscito a completare la richiesta nel tempo previsto.", toolsUsed);
    }

    private String dispatchTool(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "getAccountBalances" -> financeTools.getAccountBalances();
                case "getRecentTransactions" -> financeTools.getRecentTransactions(
                        args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : null);
                case "getTransactionsInPeriod" -> financeTools.getTransactionsInPeriod(
                        (String) args.get("startDate"), (String) args.get("endDate"));
                case "getPeriodTotals" -> financeTools.getPeriodTotals(
                        (String) args.get("startDate"), (String) args.get("endDate"));
                case "getActiveBudgets" -> financeTools.getActiveBudgets();
                case "getMonthlyTrend" -> financeTools.getMonthlyTrend(
                        args.containsKey("months") ? ((Number) args.get("months")).intValue() : null);
                case "getCategoryBreakdown" -> financeTools.getCategoryBreakdown(
                        (String) args.get("startDate"), (String) args.get("endDate"));
                case "getMonthlyProjection" -> financeTools.getMonthlyProjection();
                case "getCryptoPortfolio" -> financeTools.getCryptoPortfolio();
                case "listCategories" -> financeTools.listCategories();
                default -> "Tool non trovato: " + name;
            };
        } catch (Exception e) {
            log.error("[ChatService] Errore esecuzione tool '{}': {}", name, e.getMessage());
            return "Errore nell'esecuzione del tool " + name + ": " + e.getMessage();
        }
    }

    private Tool buildFinanceTool() {
        Schema strSchema = Schema.builder().type("STRING").build();
        Schema intSchema = Schema.builder().type("INTEGER").build();

        return Tool.builder().functionDeclarations(
                FunctionDeclaration.builder()
                        .name("getAccountBalances")
                        .description("Restituisce la lista dei conti bancari dell'utente con saldo e valuta, più il saldo totale convertito nella valuta di default dell'utente.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("getRecentTransactions")
                        .description("Restituisce le ultime transazioni dell'utente. Limit massimo 50.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of("limit", Schema.builder().type("INTEGER")
                                        .description("Numero di transazioni da restituire (default 10, max 50)").build()))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getTransactionsInPeriod")
                        .description("Restituisce le transazioni dell'utente in un intervallo di date. Max 5 anni di range.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "startDate", Schema.builder().type("STRING").description("Data inizio in formato yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine in formato yyyy-MM-dd").build()))
                                .required(List.of("startDate", "endDate"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getPeriodTotals")
                        .description("Restituisce il totale entrate, uscite e saldo netto per un periodo specifico.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "startDate", Schema.builder().type("STRING").description("Data inizio in formato yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine in formato yyyy-MM-dd").build()))
                                .required(List.of("startDate", "endDate"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getActiveBudgets")
                        .description("Restituisce i budget attivi dell'utente con limite, importo speso e percentuale di utilizzo.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("getMonthlyTrend")
                        .description("Restituisce il trend mensile di entrate e uscite degli ultimi N mesi.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of("months", Schema.builder().type("INTEGER")
                                        .description("Numero di mesi da analizzare (default 6, max 24)").build()))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getCategoryBreakdown")
                        .description("Restituisce il breakdown delle spese per categoria in un intervallo di date.")
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of(
                                        "startDate", Schema.builder().type("STRING").description("Data inizio in formato yyyy-MM-dd").build(),
                                        "endDate", Schema.builder().type("STRING").description("Data fine in formato yyyy-MM-dd").build()))
                                .required(List.of("startDate", "endDate"))
                                .build())
                        .build(),
                FunctionDeclaration.builder()
                        .name("getMonthlyProjection")
                        .description("Restituisce la proiezione di entrate e uscite per la fine del mese corrente basata sul ritmo attuale.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("getCryptoPortfolio")
                        .description("Restituisce il valore del portafoglio crypto dell'utente nella valuta di default.")
                        .build(),
                FunctionDeclaration.builder()
                        .name("listCategories")
                        .description("Restituisce la lista delle categorie disponibili per l'utente (personali + predefinite).")
                        .build()
        ).build();
    }

    private static boolean supportsThinking(String modelName) {
        return modelName.startsWith("gemini-") || modelName.startsWith("gemma-4-");
    }

    private static ThinkingConfig buildThinkingConfig(String modelName, int budget, String level) {
        if (modelName.startsWith("gemma-4-")) {
            return ThinkingConfig.builder().thinkingLevel(level).includeThoughts(false).build();
        }
        return ThinkingConfig.builder().thinkingBudget(budget).includeThoughts(false).build();
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

    private record ChatResult(String replyText, List<String> toolsUsed) {}
}
