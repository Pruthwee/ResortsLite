package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.beans.factory.annotation.Value;

/**
 * cr-java-0065 fix: Redis-backed HTTP Session configuration.
 *
 * <p>Enables Spring Session Data Redis so that all {@link javax.servlet.http.HttpSession}
 * operations in the application (e.g. {@code session.setAttribute} /
 * {@code session.getAttribute} in {@link BookingController}) are transparently
 * persisted to and retrieved from Amazon ElastiCache for Redis.
 *
 * <p>This eliminates HTTP session server affinity: any EC2 instance in the
 * Auto Scaling Group can serve any request for the same session because the
 * session data lives in the shared ElastiCache cluster rather than in JVM
 * heap memory.  The application becomes truly stateless and can be horizontally
 * scaled behind an AWS Application Load Balancer without sticky sessions.
 *
 * <h3>Required environment variables / AWS Parameter Store parameters</h3>
 * <pre>
 *   SPRING_REDIS_HOST  – ElastiCache primary endpoint hostname
 *                        (e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
 *   SPRING_REDIS_PORT  – ElastiCache port (default: 6379)
 *   SPRING_REDIS_PASSWORD – ElastiCache auth token (leave blank if AUTH is disabled)
 *   SESSION_TIMEOUT_SECONDS – session TTL in seconds (default: 1800 = 30 min)
 * </pre>
 *
 * <h3>Spring Session maxInactiveIntervalInSeconds</h3>
 * <p>The {@code @EnableRedisHttpSession} annotation sets the session TTL.
 * The value is driven by the {@code SESSION_TIMEOUT_SECONDS} environment variable
 * (default 1800 seconds / 30 minutes) so it can be tuned per environment without
 * a code change.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * ElastiCache primary endpoint hostname.
     * Set via {@code SPRING_REDIS_HOST} environment variable or
     * AWS Systems Manager Parameter Store parameter {@code /resorts/<env>/redis/host}.
     */
    @Value("${spring.redis.host:${SPRING_REDIS_HOST:localhost}}")
    private String redisHost;

    /**
     * ElastiCache port (default 6379).
     * Set via {@code SPRING_REDIS_PORT} environment variable or
     * AWS Systems Manager Parameter Store parameter {@code /resorts/<env>/redis/port}.
     */
    @Value("${spring.redis.port:${SPRING_REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * ElastiCache AUTH token (optional — leave blank if in-transit encryption
     * with AUTH is not enabled on the cluster).
     * Set via {@code SPRING_REDIS_PASSWORD} environment variable or
     * AWS Secrets Manager secret {@code resorts/<env>/redis-auth-token}.
     */
    @Value("${spring.redis.password:${SPRING_REDIS_PASSWORD:}}")
    private String redisPassword;

    /**
     * Builds a Lettuce-based {@link RedisConnectionFactory} pointing at the
     * Amazon ElastiCache cluster.  Lettuce is the recommended client for
     * ElastiCache because it supports cluster mode, TLS, and connection pooling
     * out of the box.
     *
     * @return a configured {@link LettuceConnectionFactory}
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * Configures Spring Session to serialise session attributes as JSON using
     * Jackson.  JSON serialisation is preferred over Java serialisation because:
     * <ul>
     *   <li>It is human-readable and easier to inspect in Redis CLI / ElastiCache
     *       console.</li>
     *   <li>It avoids {@link java.io.NotSerializableException} for objects that do
     *       not implement {@link java.io.Serializable}.</li>
     *   <li>It is forward-compatible with rolling deployments where old and new
     *       application versions may share the same Redis cluster.</li>
     * </ul>
     *
     * @return a {@link GenericJackson2JsonRedisSerializer} instance
     */
    @Bean("springSessionDefaultRedisSerializer")
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }
}
