package com.demo.resortslite.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis Session Configuration for Amazon ElastiCache
 * FIXED cr-java-0065: Enables distributed session management using Redis
 * 
 * This configuration replaces in-memory HTTP session storage with Redis-backed
 * session storage, enabling stateless application instances that can scale
 * horizontally across multiple EC2 instances or containers.
 * 
 * Session data is now stored in Amazon ElastiCache for Redis, making it
 * accessible to all application instances behind the AWS Application Load Balancer.
 * 
 * Configuration:
 * - maxInactiveIntervalInSeconds: Session timeout (30 minutes)
 * - redisNamespace: Session key prefix in Redis
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800, redisNamespace = "resortslite:session")
public class RedisSessionConfig {
    // Spring Session automatically configures Redis-backed session repository
    // No additional beans required - configuration is handled via application.properties
}
