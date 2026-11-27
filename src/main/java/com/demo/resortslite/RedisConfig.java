package com.demo.resortslite;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Shared state for horizontally scaled pods: HttpSession (Spring Session) and the
 * booking cache (Spring Cache) are both stored in Redis (e.g. Amazon ElastiCache).
 * Connection details come from REDIS_HOST, REDIS_PORT, REDIS_PASSWORD and REDIS_SSL.
 */
@Configuration
@EnableCaching
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisConfig {

    /** ElastiCache does not allow the CONFIG command; keyspace events are set on the server side. */
    @Bean
    public static ConfigureRedisAction configureRedisAction() {
        return ConfigureRedisAction.NO_OP;
    }
}
