package it.iacovelli.nexabudgetbe.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

        public static final String BANK_ACCOUNTS_CACHE = "bankAccounts";
        public static final String GOCARDLESS_TRANSACTIONS_CACHE = "gocardlessTransactions";
        public static final String GOCARDLESS_BANKS_CACHE = "gocardlessCountryBanks";
        public static final String CRYPTO_PRICES_CACHE = "cryptoPrices";
        public static final String EXCHANGE_RATES_CACHE = "exchangeRates";
        public static final String PORTFOLIO_CACHE = "portfolio";
        public static final Duration CRYPTO_CACHE_TTL = Duration.ofMinutes(5);
        public static final Duration CACHE_TTL = Duration.ofHours(6);

        @Bean
        public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
                ObjectMapper cacheObjectMapper = objectMapper.copy();
                cacheObjectMapper.registerModule(new JavaTimeModule());
                cacheObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                cacheObjectMapper.activateDefaultTypingAsProperty(
                                cacheObjectMapper.getPolymorphicTypeValidator(),
                                ObjectMapper.DefaultTyping.NON_FINAL,
                                "@class");

                RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(CACHE_TTL)
                                .disableCachingNullValues()
                                .serializeKeysWith(
                                                RedisSerializationContext.SerializationPair.fromSerializer(
                                                                new StringRedisSerializer()))
                                .serializeValuesWith(
                                                RedisSerializationContext.SerializationPair.fromSerializer(
                                                                new GenericJackson2JsonRedisSerializer(
                                                                                cacheObjectMapper)));

                return RedisCacheManager.builder(connectionFactory)
                                .cacheDefaults(config)
                                .build();
        }
}
