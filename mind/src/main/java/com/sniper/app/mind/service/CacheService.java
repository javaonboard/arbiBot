package com.sniper.app.mind.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class CacheService {

    private final Map<String, CachedValue<?>> cache = new HashMap<>();

    // Method to get cached value with class casting
    public <T> T getCachedValue(String key, Class<T> clazz) {
        CachedValue<?> cachedValue = cache.get(key);
        if (cachedValue != null && !isExpired(cachedValue)) {
            return clazz.cast(cachedValue.value);
        }
        return null;
    }

    // Method to cache a value with a custom expiration time in seconds
    public void cacheValueWithExpiry(String key, Object value, long expiryTimeInSeconds) {
        long expiryTimeInMillis = expiryTimeInSeconds * 1000;  // Convert seconds to milliseconds
        cache.put(key, new CachedValue<>(value, System.currentTimeMillis(), expiryTimeInMillis));
    }

    // Method to cache a value with default expiration (e.g., 1 minute)
    public void cacheValue(String key, Object value) {
        cache.put(key, new CachedValue<>(value, System.currentTimeMillis(), 60_000));  // Default 1 minute
    }

    // Check if a cached value is expired
    private boolean isExpired(CachedValue<?> cachedValue) {
        return System.currentTimeMillis() - cachedValue.timestamp >= cachedValue.expiryTime;
    }

    // Nested static class to represent a cached value with an expiry time
    private static class CachedValue<T> {
        T value;
        long timestamp;
        long expiryTime;  // Expiry time in milliseconds

        CachedValue(T value, long timestamp, long expiryTime) {
            this.value = value;
            this.timestamp = timestamp;
            this.expiryTime = expiryTime;
        }
    }
}
