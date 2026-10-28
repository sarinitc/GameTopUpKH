package org.example.gametopupkh.auth.passwordreset;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window limiter. Bounds email flooding by destination and abuse by
 * source address. The counters are intentionally keyed before account lookup so unknown
 * addresses are limited in exactly the same way as registered ones.
 */
@Component
public class PasswordResetRateLimiter {

    private static final int MAX_KEYS_BEFORE_CLEANUP = 10_000;

    private final PasswordResetProperties properties;
    private final ConcurrentHashMap<String, WindowCounter> emailWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WindowCounter> ipWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WindowCounter> resetWindows = new ConcurrentHashMap<>();

    public PasswordResetRateLimiter(PasswordResetProperties properties) {
        this.properties = properties;
    }

    /**
     * @return {@code 0} when the request is allowed, otherwise the retry-after seconds
     */
    public long tryRequestForEmail(String email) {
        return acquire(emailWindows, "email:" + email, properties.getRequestsPerEmailPerWindow());
    }

    /**
     * @return {@code 0} when the request is allowed, otherwise the retry-after seconds
     */
    public long tryRequestForIp(String ipAddress) {
        return acquire(ipWindows, "ip:" + ipAddress, properties.getRequestsPerIpPerWindow());
    }

    /**
     * @return {@code 0} when the attempt is allowed, otherwise the retry-after seconds
     */
    public long tryResetForIp(String ipAddress) {
        return acquire(resetWindows, "reset:" + ipAddress, properties.getResetAttemptsPerIpPerWindow());
    }

    private long acquire(ConcurrentHashMap<String, WindowCounter> windows, String key, int configuredLimit) {
        long nowSec = Instant.now().getEpochSecond();
        long windowSec = Math.max(1, properties.getRateLimitWindow().toSeconds());
        int limit = Math.max(1, configuredLimit);

        if (windows.size() > MAX_KEYS_BEFORE_CLEANUP) {
            windows.entrySet().removeIf(entry -> nowSec - entry.getValue().lastWindowStart() > windowSec * 2);
        }
        return windows.computeIfAbsent(key, ignored -> new WindowCounter())
                .tryAcquire(nowSec, limit, windowSec);
    }

    private static final class WindowCounter {

        private final AtomicLong windowStartSec = new AtomicLong(0);
        private final AtomicInteger count = new AtomicInteger(0);

        long lastWindowStart() {
            return windowStartSec.get();
        }

        synchronized long tryAcquire(long nowSec, int limit, long windowSec) {
            long currentStart = windowStartSec.get();
            if (currentStart == 0 || nowSec - currentStart >= windowSec) {
                windowStartSec.set(nowSec);
                count.set(1);
                return 0;
            }
            if (count.get() >= limit) {
                return Math.max(1, windowSec - (nowSec - currentStart));
            }
            count.incrementAndGet();
            return 0;
        }
    }
}
