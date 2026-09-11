package com.demo.resortslite;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisConfig — Spring Data Redis configuration for the distributed booking cache.
 *
 * <p>cr-java-0067 fix: Configures a {@link RedisTemplate}{@code <String, Object>} bean
 * backed by Amazon ElastiCache for Redis.  The template uses:
 * <ul>
 *   <li>{@link StringRedisSerializer} for keys — human-readable key names in Redis.</li>
 *   <li>{@link Jackson2JsonRedisSerializer} for values — JSON serialisation of booking
 *       {@code Map<String, Object>} entries, enabling cross-instance deserialization.</li>
 * </ul>
 *
 * <p>Every cache entry written via this template is stored with an explicit TTL
 * (configured via {@code app.cache.booking-ttl-minutes}, default 30 minutes), ensuring
 * automatic expiration of stale entries and preventing indefinite memory growth in the
 * Redis cluster.  Because the cache is stored in the shared ElastiCache cluster, all
 * application instances read the same data, enabling true stateless horizontal scaling
 * without sticky sessions or instance-local state.
 *
 * <p>The Redis connection details (host, port, password) are supplied via
 * {@code spring.redis.*} properties, which are driven by environment variables
 * ({@code SPRING_REDIS_HOST}, {@code SPRING_REDIS_PORT}, {@code SPRING_REDIS_PASSWORD})
 * so no credentials are hard-coded in source.
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a {@link RedisTemplate} with JSON value serialization for the
     * distributed booking cache (cr-java-0067 fix).
     *
     * @param connectionFactory the auto-configured {@link RedisConnectionFactory}
     *                          pointing to the Amazon ElastiCache Redis endpoint
     * @return a fully configured {@link RedisTemplate}{@code <String, Object>}
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys — produces readable Redis key names
        // e.g. "booking:cache:BK-A1B2C3D4"
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use Jackson JSON serializer for values — enables cross-instance deserialization
        // of Map<String, Object> booking entries stored in the ElastiCache Redis cluster
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);

        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(Object.class);
        jsonSerializer.setObjectMapper(objectMapper);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
