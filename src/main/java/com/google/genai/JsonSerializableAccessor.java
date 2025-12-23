package com.google.genai;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Accessor to get the package-private ObjectMapper from JsonSerializable.
 * This class must be in package com.google.genai to access the field.
 */
public class JsonSerializableAccessor {
    public static ObjectMapper getObjectMapper() {
        return JsonSerializable.objectMapper;
    }
}
