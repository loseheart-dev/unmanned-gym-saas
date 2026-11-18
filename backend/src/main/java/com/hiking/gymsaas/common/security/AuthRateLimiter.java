package com.hiking.gymsaas.common.security;

import com.hiking.gymsaas.common.exception.BusinessException;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.util.Hashes;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("!bootstrap")
public class AuthRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);
    private final StringRedisTemplate redis;

    public AuthRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public void rejectAtLimit(String scope, String identifier, int limit) {
        try {
            String count = redis.opsForValue().get(key(scope, identifier));
            if (count != null && Long.parseLong(count) >= limit) throw new BusinessException(ErrorCode.RATE_LIMITED);
        } catch (org.springframework.data.redis.RedisSystemException | RedisConnectionFailureException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public void check(String scope, String identifier, int limit, int seconds) {
        try {
            Long count = redis.execute(INCREMENT, List.of(key(scope, identifier)), Integer.toString(seconds));
            if (count == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            if (count > limit) throw new BusinessException(ErrorCode.RATE_LIMITED);
        } catch (org.springframework.data.redis.RedisSystemException | RedisConnectionFailureException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String key(String scope, String identifier) { return "gym:auth:" + scope + ":" + Hashes.sha256(identifier); }
}
