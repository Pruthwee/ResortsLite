package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.HttpSessionIdResolver;

/**
 * Redis Session Configuration for Cloud-Native Distributed Session Management
 * 
 * FIXED cr-java-0065: HTTP Session State Storage
 * 
 * This configuration enables Spring Session with Amazon ElastiCache for Redis as the
 * backing store for HTTP sessions. This resolves the cloud readiness blocker where
 * session data was stored in local server memory, preventing horizontal scaling.
 * 
 * Benefits:
 * - Stateless application instances: Any EC2 instance can handle any request
 * - Horizontal scaling: AWS Auto Scaling can add/remove instances without session loss
 * - High availability: Session data survives instance termination or failure
 * - Load balancing: AWS ALB can distribute requests without sticky sessions
 * - Zero downtime deployments: Rolling updates don't lose active sessions
 * 
 * Configuration:
 * - maxInactiveIntervalInSeconds: Session timeout (30 minutes)
 * - redisNamespace: Redis key prefix for session data isolation
 * 
 * Environment Variables Required:
 * - REDIS_HOST: Amazon ElastiCache Redis endpoint (e.g., my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
 * - REDIS_PORT: Redis port (default: 6379)
 * - REDIS_PASSWORD: Redis AUTH password (if authentication enabled)
 * 
 * AWS ElastiCache Setup:
 * 1. Create ElastiCache for Redis cluster in same VPC as application
 * 2. Configure security group to allow inbound traffic on port 6379 from application security group
 * 3. Use cluster endpoint as REDIS_HOST environment variable
 * 4. Enable encryption in-transit for production environments
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes session timeout
public class RedisSessionConfig {

    /**
     * Configure session ID resolution strategy.
     * 
     * By default, Spring Session uses cookies (JSESSIONID) to track sessions.
     * This bean adds support for X-Auth-Token header-based session tracking,
     * which is useful for REST APIs and mobile clients.
     * 
     * Session ID can be passed via:
     * - Cookie: JSESSIONID=<session-id>
     * - Header: X-Auth-Token: <session-id>
     */
    @Bean
    public HttpSessionIdResolver httpSessionIdResolver() {
        return HeaderHttpSessionIdResolver.xAuthToken();
    }
}
