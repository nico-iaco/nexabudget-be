package it.iacovelli.nexabudgetbe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.UUID;

@Configuration
@ImportRuntimeHints(NativeRuntimeHints.class)
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NativeRuntimeHints.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        log.info("Registering native runtime hints...");

        // Register UUID[] for reflection (instantiation)
        hints.reflection().registerType(UUID[].class, memberCategories ->
                memberCategories.withMembers(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));

        // Register UUID for full reflection
        hints.reflection().registerType(UUID.class, MemberCategory.values());

        // Register SpringDoc CGLIB proxy field access
        // This handles: org.graalvm.nativeimage.MissingReflectionRegistrationError: Cannot reflectively read or write field ... CGLIB$FACTORY_DATA
        try {
            hints.reflection().registerType(TypeReference.of("org.springdoc.core.providers.SpringWebProvider$$SpringCGLIB$$0"), hint ->
                    hint.withField("CGLIB$FACTORY_DATA")
            );
        } catch (Exception e) {
            log.warn("Could not register hint for SpringWebProvider proxy: {}", e.getMessage());
        }
    }
}

