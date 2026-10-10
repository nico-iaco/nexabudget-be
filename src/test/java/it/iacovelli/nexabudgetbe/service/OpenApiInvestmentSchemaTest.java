package it.iacovelli.nexabudgetbe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.iacovelli.nexabudgetbe.config.TestConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Il Swagger è il contratto per i client generati: le risposte devono dichiarare quali campi sono sempre presenti e
 * quali possono essere null (altrimenti un generatore li segna tutti opzionali), e gli endpoint i codici di errore.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfig.class)
class OpenApiInvestmentSchemaTest {

    @Value("${local.server.port}")
    private int port;

    private JsonNode docs;

    private JsonNode docs() throws Exception {
        if (docs == null) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            docs = new ObjectMapper().readTree(r.body());
        }
        return docs;
    }

    private JsonNode schema(String name) throws Exception {
        JsonNode s = docs().path("components").path("schemas").path(name);
        assertFalse(s.isMissingNode(), "schema " + name + " mancante");
        return s;
    }

    private static Set<String> required(JsonNode schema) {
        return StreamSupport.stream(schema.path("required").spliterator(), false).map(JsonNode::asText).collect(Collectors.toSet());
    }

    private static Set<String> properties(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    /** Ogni campo di una risposta è o required o nullable: niente ambiguità per chi genera il client. */
    private void assertEveryFieldIsRequiredOrNullable(String name) throws Exception {
        JsonNode s = schema(name);
        Set<String> required = required(s);
        for (String field : properties(s)) {
            JsonNode p = s.path("properties").path(field);
            boolean nullable = p.path("nullable").asBoolean(false)
                    || (p.path("type").isArray() && StreamSupport.stream(p.path("type").spliterator(), false).anyMatch(t -> "null".equals(t.asText())));
            assertTrue(required.contains(field) || nullable, name + "." + field + " non è né required né nullable");
        }
    }

    @Test
    void responseSchemas_declareRequiredAndNullableFields() throws Exception {
        for (String name : List.of("AssetResponse", "OperationResponse", "PositionResponse", "PortfolioResponse",
                "AllocationItem", "PerformanceResponse", "HistoryPoint", "HistoryResponse", "SearchResult",
                "NetWorthResponse", "NetWorthPoint", "NetWorthHistoryResponse")) {
            assertEveryFieldIsRequiredOrNullable(name);
        }
    }

    @Test
    void assetResponse_contract() throws Exception {
        JsonNode s = schema("AssetResponse");
        assertEquals(Set.of("id", "assetType", "name", "isin", "symbol", "currency", "priceSource", "manualPrice",
                "manualPriceAt", "couponRate", "couponFrequency", "maturityDate", "createdAt"), properties(s));
        assertEquals(Set.of("id", "assetType", "name", "currency", "priceSource", "createdAt"), required(s));
    }

    @Test
    void operationResponse_contract() throws Exception {
        JsonNode s = schema("OperationResponse");
        assertEquals(Set.of("id", "assetId", "assetName", "type", "operationDate", "fees"), required(s));
        assertTrue(properties(s).containsAll(Set.of("quantity", "price", "amount", "notes")));
    }

    @Test
    void positionResponse_nullableMoneyFields() throws Exception {
        JsonNode s = schema("PositionResponse");
        Set<String> required = required(s);
        for (String f : List.of("price", "marketValue", "costBasis", "unrealizedPl", "unrealizedPlPercent", "realizedPl", "income")) {
            assertFalse(required.contains(f), f + " può essere null: non deve essere required");
        }
        assertTrue(required.containsAll(Set.of("assetId", "quantity", "avgPrice", "stale", "currency")));
    }

    @Test
    void netWorthResponse_componentsAreNullable() throws Exception {
        Set<String> required = required(schema("NetWorthResponse"));
        assertTrue(required.containsAll(Set.of("total", "complete", "warnings", "possibleDoubleCounting")));
        for (String f : List.of("liquidity", "crypto", "investments")) {
            assertFalse(required.contains(f), f);
        }
    }

    @Test
    void requests_describeValidationAndOptionalFields() throws Exception {
        assertEquals(Set.of("assetType", "name"), required(schema("AssetRequest")));
        // isin e symbol non sono obbligatori nel PUT (solo tipo, nome e fonte prezzo)
        assertEquals(Set.of("assetType", "name", "priceSource"), required(schema("AssetUpdateRequest")));
        assertEquals(Set.of("type", "operationDate"), required(schema("OperationRequest")));
        assertFalse(schema("OperationRequest").path("properties").path("amount").path("description").asText().isBlank());
    }

    @Test
    void everyEndpoint_declaresItsSuccessResponse_withASchemaWhenItReturnsABody() throws Exception {
        // Aggiungere un @ApiResponse (es. per gli errori) elimina la risposta di successo automatica di springdoc:
        // senza di lei un client generato non conosce il tipo restituito
        int checked = 0;
        var paths = docs().path("paths").fields();
        while (paths.hasNext()) {
            var path = paths.next();
            if (!(path.getKey().startsWith("/api/investments") || path.getKey().startsWith("/api/net-worth")
                    || path.getKey().equals("/api/reports/investment-performance")
                    || path.getKey().equals("/api/reports/net-worth-trend"))) {
                continue;
            }
            var ops = path.getValue().fields();
            while (ops.hasNext()) {
                var op = ops.next();
                JsonNode responses = op.getValue().path("responses");
                List<String> success = new ArrayList<>();
                responses.fieldNames().forEachRemaining(c -> {
                    if (c.startsWith("2")) {
                        success.add(c);
                    }
                });
                String what = op.getKey().toUpperCase() + " " + path.getKey();
                assertFalse(success.isEmpty(), what + ": nessuna risposta 2xx dichiarata");
                for (String code : success) {
                    if (!code.equals("204")) {
                        assertTrue(responses.path(code).path("content").size() > 0, what + " " + code + ": manca lo schema della risposta");
                    }
                }
                checked++;
            }
        }
        assertTrue(checked >= 18, "attesi almeno 18 endpoint, trovati " + checked);
    }

    @Test
    void endpoints_declareErrorResponses() throws Exception {
        JsonNode paths = docs().path("paths");
        assertTrue(paths.path("/api/investments/assets").path("post").path("responses").has("409"));
        assertTrue(paths.path("/api/investments/assets/{id}/operations").path("post").path("responses").has("409"));
        assertTrue(paths.path("/api/investments/operations/{id}").path("delete").path("responses").has("409"));
        assertTrue(paths.path("/api/investments/performance").path("get").path("responses").has("400"));
    }
}
