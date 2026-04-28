package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-based distributed cache service
 * Replaces local in-memory caching with distributed cache for horizontal scalability
 */
@Service
public class RedisCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Store value in distributed cache with TTL
     * @param key Cache key
     * @param value Cache value
     * @param ttlMinutes Time to live in minutes
     */
    public void put(String key, Object value, long ttlMinutes) {
        redisTemplate.opsForValue().set(key, value, ttlMinutes, TimeUnit.MINUTES);
    }

    /**
     * Retrieve value from distributed cache
     * @param key Cache key
     * @return Cached value or null if not found
     */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * Remove value from distributed cache
     * @param key Cache key
     */
    public void remove(String key) {
        redisTemplate.delete(key);
    }

    /**
     * Check if key exists in cache
     * @param key Cache key
     * @return true if key exists
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
