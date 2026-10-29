package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Stores the HttpSession in Redis (Amazon ElastiCache) instead of the JVM heap, so sessions
 * survive pod restarts and are shared by all replicas. Connection details come from the
 * REDIS_HOST, REDIS_PORT and REDIS_PASSWORD environment variables (see application.properties).
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {
}
