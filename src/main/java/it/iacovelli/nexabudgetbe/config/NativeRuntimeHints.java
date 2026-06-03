package it.iacovelli.nexabudgetbe.config;

import it.iacovelli.nexabudgetbe.dto.*;
import it.iacovelli.nexabudgetbe.model.*;
import it.iacovelli.nexabudgetbe.service.AiCategorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.List;
import java.util.UUID;

@Configuration
@ImportRuntimeHints(NativeRuntimeHints.class)
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NativeRuntimeHints.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        log.info("Registering native runtime hints...");

        // ─── JDK types ──────────────────────────────────────────────────────────
        hints.reflection().registerType(UUID[].class, memberCategories ->
                memberCategories.withMembers(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
        hints.reflection().registerType(UUID.class, MemberCategory.values());

        // ─── SpringDoc CGLIB proxy ───────────────────────────────────────────────
        try {
            hints.reflection().registerType(TypeReference.of("org.springdoc.core.providers.SpringWebProvider$$SpringCGLIB$$0"), hint ->
                    hint.withField("CGLIB$FACTORY_DATA")
            );
        } catch (Exception e) {
            log.warn("Could not register hint for SpringWebProvider proxy: {}", e.getMessage());
        }

        // ─── New JPA entities (Phase 4 & 5) ─────────────────────────────────────
        // Hibernate's AOT processor scans @Entity classes, but explicit registration
        // ensures CGLIB proxies and Lombok-generated accessors are also reachable.
        List<Class<?>> entities = List.of(
                AuditLog.class,
                ApiKey.class,
                BudgetTemplate.class,
                BudgetAlert.class
        );
        for (Class<?> entity : entities) {
            hints.reflection().registerType(entity, MemberCategory.values());
        }

        // ─── New enums ───────────────────────────────────────────────────────────
        hints.reflection().registerType(RecurrenceType.class, MemberCategory.values());

        // ─── New DTOs ────────────────────────────────────────────────────────────
        // Spring MVC AOT registers DTO types seen in @RestController signatures,
        // but inner static classes can be missed. Registering them explicitly here
        // covers Jackson serialization in AuditAspect.serialize() and edge cases.
        List<Class<?>> dtos = List.of(
                // Audit log
                AuditLogDto.AuditLogResponse.class,
                // API keys
                ApiKeyDto.CreateApiKeyRequest.class,
                ApiKeyDto.CreateApiKeyResponse.class,
                ApiKeyDto.UpdateApiKeyRequest.class,
                ApiKeyDto.ApiKeyResponse.class,
                // Import
                ImportDto.CsvColumnMapping.class,
                ImportDto.ImportedTransactionPreview.class,
                ImportDto.ImportPreviewResponse.class,
                ImportDto.ImportConfirmRequest.class,
                ImportDto.ImportResult.class,
                // Budget template
                BudgetTemplateDto.BudgetTemplateRequest.class,
                BudgetTemplateDto.BudgetTemplateResponse.class,
                // Budget alert
                BudgetAlertDto.BudgetAlertRequest.class,
                BudgetAlertDto.BudgetAlertResponse.class,
                // Report
                ReportDto.MonthlyTrendItem.class,
                ReportDto.CategoryBreakdownItem.class,
                ReportDto.CategoryBreakdownResponse.class,
                ReportDto.MonthComparisonItem.class,
                ReportDto.MonthComparisonResponse.class,
                ReportDto.MonthlyProjection.class,
                // Trash
                AccountDto.TrashAccountResponse.class
        );
        for (Class<?> dto : dtos) {
            hints.reflection().registerType(dto, MemberCategory.values());
        }

        // ─── AI response records ─────────────────────────────────────────────────
        // Inner record classes used by BeanOutputConverter (Jackson) are not picked
        // up by Spring MVC AOT scanning and need explicit registration so that
        // getRecordComponents() and the accessor methods are available at runtime.
        hints.reflection().registerType(AiCategorizationService.AiCategoryResponse.class,
                MemberCategory.values());

        // ─── Spring AI MongoDB Atlas Vector Store ────────────────────────────────
        // MongoDBDocument is an inner record of MongoDBAtlasVectorStore with no
        // @Document/@Id annotations. In native mode, Spring Data MongoDB cannot
        // access its record components (id, content, metadata, embedding) via
        // reflection without explicit registration — the document is saved with
        // only _id and _class. Full MemberCategory registration enables the
        // record constructor, component accessors, and field introspection.
        hints.reflection().registerType(MongoDBAtlasVectorStore.MongoDBDocument.class,
                MemberCategory.values());
        // Spring Data MongoDB uses RecordInstantiatorProperty to map record
        // components; register the filter expression converter used at search time.
        try {
            hints.reflection().registerType(
                    TypeReference.of("org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasFilterExpressionConverter"),
                    MemberCategory.values());
        } catch (Exception e) {
            log.warn("Could not register MongoDBAtlasFilterExpressionConverter hint: {}", e.getMessage());
        }

        // ─── OpenPDF resources ───────────────────────────────────────────────────
        // Native image doesn't include classpath resources automatically.
        // OpenPDF needs its error message files (.lng), font metrics (.afm),
        // font property files (.properties), and CMap files (.cmap) at runtime.
        hints.resources().registerPattern("com/lowagie/text/error_messages/*");
        hints.resources().registerPattern("com/lowagie/text/pdf/fonts/*");
        hints.resources().registerPattern("font-fallback/*");
        hints.resources().registerPattern("com/lowagie/text/version.properties");

        // ─── Apache Commons CSV ──────────────────────────────────────────────────
        // CSVFormat uses an internal Predicate via lambda — register the top-level class
        // and its nested Builder to avoid missing-method errors at runtime.
        List<String> csvClasses = List.of(
                "org.apache.commons.csv.CSVFormat",
                "org.apache.commons.csv.CSVFormat$Builder",
                "org.apache.commons.csv.CSVParser",
                "org.apache.commons.csv.CSVRecord"
        );
        for (String className : csvClasses) {
            try {
                hints.reflection().registerType(TypeReference.of(className), MemberCategory.values());
            } catch (Exception e) {
                log.warn("Could not register Commons CSV hint for {}: {}", className, e.getMessage());
            }
        }

        // ─── Coinbase SDK ───────────────────────────────────────────────────────
        // The Coinbase Advanced SDK uses Jackson for JSON binding and likely
        // reflection for service instantiation. Registering all relevant models
        // and service implementations.
        List<String> coinbaseModels = List.of(
                // Accounts
                "com.coinbase.advanced.model.accounts.Account",
                "com.coinbase.advanced.model.accounts.ListAccountsResponse",
                // Common
                "com.coinbase.advanced.model.common.Amount",
                "com.coinbase.advanced.model.common.Pagination",
                // Portfolios
                "com.coinbase.advanced.model.portfolios.Breakdown",
                "com.coinbase.advanced.model.portfolios.GetPortfolioBreakdownResponse",
                "com.coinbase.advanced.model.portfolios.ListPortfoliosResponse",
                "com.coinbase.advanced.model.portfolios.Portfolio",
                "com.coinbase.advanced.model.portfolios.PortfolioBalances",
                "com.coinbase.advanced.model.portfolios.SpotPosition",
                "com.coinbase.advanced.model.portfolios.UnrealizedPnl"
        );

        // Register models for reflection (Jackson serialization/deserialization)
        for (String className : coinbaseModels) {
            try {
                hints.reflection().registerType(TypeReference.of(className), MemberCategory.values());
            } catch (Exception e) {
                log.warn("Could not register Coinbase model hint for {}: {}", className, e.getMessage());
            }
        }

        // Register specific core classes
        hints.reflection().registerType(com.coinbase.advanced.credentials.CoinbaseAdvancedCredentials.class, MemberCategory.values());
        hints.reflection().registerType(com.coinbase.advanced.client.CoinbaseAdvancedClient.class, MemberCategory.values());
        hints.reflection().registerType(com.coinbase.advanced.factory.CoinbaseAdvancedServiceFactory.class, MemberCategory.values());

        // Register service implementations
        List<String> coinbaseServices = List.of(
                "com.coinbase.advanced.accounts.AccountsServiceImpl",
                "com.coinbase.advanced.orders.OrdersServiceImpl",
                "com.coinbase.advanced.portfolios.PortfoliosServiceImpl",
                "com.coinbase.advanced.products.ProductsServiceImpl"
        );
        for (String service : coinbaseServices) {
            hints.reflection().registerType(TypeReference.of(service), MemberCategory.values());
        }

        // Coinbase credentials uses CoinbaseCredentials interface (core-java)
        try {
            hints.reflection().registerType(TypeReference.of("com.coinbase.core.credentials.CoinbaseCredentials"), MemberCategory.values());
        } catch (Exception e) {
            log.warn("Could not register CoinbaseCredentials hint: {}", e.getMessage());
        }

        // ─── Spring AI MCP server (WebMVC, STATELESS transport) ─────────────────
        // McpSchema inner types (102 JSON-RPC DTOs) are auto-registered by
        // McpHints via META-INF/spring/aot.factories in spring-ai-mcp.jar.
        // Auto-configuration and @ConfigurationProperties classes are handled by
        // Spring Boot AOT. The three transport-provider classes below live inside
        // the mcp-spring-webmvc jar and are registered as HandlerMapping/RouteFunction
        // contributors; without explicit hints, GraalVM may miss them because they
        // are referenced through the MVC handler-adapter reflection path.
        List<String> mcpTransportClasses = List.of(
                "org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport",
                "org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider",
                "org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider",
                "org.springframework.ai.mcp.server.webmvc.transport.HeaderUtils"
        );
        for (String className : mcpTransportClasses) {
            try {
                hints.reflection().registerType(TypeReference.of(className), MemberCategory.values());
            } catch (Exception e) {
                log.warn("Could not register MCP transport hint for {}: {}", className, e.getMessage());
            }
        }

        // McpConnectionInfo is a record returned over SSE and potentially serialized
        // by Jackson — the Builder inner class is instantiated via reflection.
        List<String> mcpConnectionClasses = List.of(
                "org.springframework.ai.mcp.McpConnectionInfo",
                "org.springframework.ai.mcp.McpConnectionInfo$Builder"
        );
        for (String className : mcpConnectionClasses) {
            try {
                hints.reflection().registerType(TypeReference.of(className), MemberCategory.values());
            } catch (Exception e) {
                log.warn("Could not register McpConnectionInfo hint for {}: {}", className, e.getMessage());
            }
        }

        // ─── Coinbase JWT signing (BouncyCastle) ──────────────────────────────
        // CoinbaseAdvancedCredentials uses BouncyCastle to parse PEM keys and
        // sign ES256 JWTs. Native image needs explicit reachability for these types.
        List<String> bouncyCastleTypes = List.of(
                "org.bouncycastle.jce.provider.BouncyCastleProvider",
                "org.bouncycastle.openssl.PEMParser",
                "org.bouncycastle.openssl.PEMKeyPair",
                "org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter",
            "org.bouncycastle.asn1.pkcs.PrivateKeyInfo",
            "org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi",
            "org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi$EC"
        );
        for (String className : bouncyCastleTypes) {
            try {
                hints.reflection().registerType(TypeReference.of(className), MemberCategory.values());
            } catch (Exception e) {
                log.warn("Could not register BouncyCastle hint for {}: {}", className, e.getMessage());
            }
        }
    }
}

