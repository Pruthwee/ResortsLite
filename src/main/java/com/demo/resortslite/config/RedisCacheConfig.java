package com.demo.resortslite.config;

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

/**
 * Redis Cache Configuration for Amazon ElastiCache
 * FIXED cr-java-0067: Replaces in-memory caching with distributed Redis cache with TTL
 * 
 * This configuration enables Spring Cache abstraction backed by Amazon ElastiCache for Redis,
 * replacing unbounded in-memory HashMap caching with a distributed cache that:
 * - Has proper TTL (Time-To-Live) policies to prevent memory growth
 * - Is shared across all application instances for cache consistency
 * - Automatically expires stale data
 * - Supports horizontal scaling without cache synchronization issues
 * 
 * Cache Configuration:
 * - Default TTL: 30 minutes (1800 seconds)
 * - Booking cache TTL: 1 hour (3600 seconds)
 * - Serialization: JSON for cross-platform compatibility
 * - Key prefix: "resortslite:cache:" for namespace isolation
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Configure Redis Cache Manager with TTL policies
     * Replaces static HashMap with distributed cache
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Default cache configuration with 30-minute TTL
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()
                        )
                )
                .prefixCacheNamesWith("resortslite:cache:")
                .disableCachingNullValues();

        // Specific cache configuration for bookings with 1-hour TTL
        RedisCacheConfiguration bookingCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()
                        )
                )
                .prefixCacheNamesWith("resortslite:cache:")
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("bookings", bookingCacheConfig)
                .transactionAware()
                .build();
    }
}
