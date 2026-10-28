package org.example.gametopupkh.player.rate;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.example.gametopupkh.player.config.PlayerVerificationProperties;
import org.springframework.stereotype.Component;

@Component
public class PlayerVerificationRateLimiter {

    private final PlayerVerificationProperties properties;
    private final ConcurrentHashMap<String, WindowCounter> userWindows = new ConcurrentHashMap<>();
    private final WindowCounter globalWindow = new WindowCounter();

    public PlayerVerificationRateLimiter(PlayerVerificationProperties properties) {
        this.properties = properties;
    }

    public long tryAcquire(String username) {
        long nowSec = Instant.now().getEpochSecond();

        int globalLimit = Math.max(1, properties.getGlobalRequestsPerMinute());
        long globalRetry = globalWindow.tryAcquire(nowSec, globalLimit);
        if (globalRetry > 0) {
            return globalRetry;
        }

        if (userWindows.size() > Math.max(10, properties.getMaxRateLimitKeys())) {
            userWindows.entrySet().removeIf(e -> nowSec - e.getValue().lastWindowStart() > 60);
        }

        int userLimit = Math.max(1, properties.getRequestsPerMinute());
        WindowCounter userCounter = userWindows.computeIfAbsent(username, k -> new WindowCounter());
        return userCounter.tryAcquire(nowSec, userLimit);
    }

    private static class WindowCounter {
        private final AtomicLong windowStartSec = new AtomicLong(0);
        private final AtomicInteger count = new AtomicInteger(0);

        long lastWindowStart() {
            return windowStartSec.get();
        }

        synchronized long tryAcquire(long nowSec, int limit) {
            long currentStart = windowStartSec.get();
            if (nowSec - currentStart >= 60) {
                windowStartSec.set(nowSec);
                count.set(1);
                return 0;
            }
            if (count.get() >= limit) {
                long elapsed = nowSec - currentStart;
                long retryAfter = 60 - elapsed;
                return Math.max(1, retryAfter);
            }
            count.incrementAndGet();
            return 0;
        }
    }
}
