package com.fintech.fintech_gateway.ratelimit;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Lua scripts for both limiter algorithms.
 *
 * <p>GCRA state is a single "theoretical arrival time" (TAT) per bucket.
 * Emission interval is {@code window / limit}; the tolerance is one emission
 * interval short of the window, so a fresh bucket admits a burst of exactly
 * {@code limit} requests and then refills one slot per interval. The long-run
 * rate is {@code limit} per window. Unlike the sorted-set fallback, GCRA does
 * not promise at most {@code limit} in every sliding window: a full burst plus
 * the refills that follow it can put up to {@code 2*limit - 1} requests inside
 * one window. That is the standard GCRA trade-off for O(1) state instead of
 * one ZSET member per request; choose {@code sorted-set} when an exact
 * per-window bound is required.</p>
 *
 * <p>All GCRA arithmetic is in integer microseconds. Epoch microseconds
 * (~1.8e15) are still below 2^53, so Lua's double numbers stay exact, while a
 * millisecond unit would floor every interval below 1 ms and silently cap a
 * bucket at 60,000 requests per minute regardless of the configured limit.</p>
 *
 * <p>The admission scripts combine blacklist, trust tier, tenant+client quota
 * and the tenant+IP abuse ceiling in one atomic round trip. The single-bucket
 * scripts are used for the rare THROTTLE penalty path, which must consume the
 * same quota bucket.</p>
 */
public final class RateLimitScripts {

    private RateLimitScripts() {
    }

    /**
     * Shared GCRA primitive. {@code gcra_check} returns
     * {@code allowed, remaining, retrySeconds, newTat, interval} and performs
     * no write; the caller commits the returned TAT only after every bucket in
     * the request has been checked. That keeps a combined admission atomic
     * without consuming one bucket when another rejects.
     *
     * <p>A cost above 1 (the risk-throttle penalty) is checked before it is
     * applied: a request cannot borrow slots that would push the bucket beyond
     * the window.</p>
     *
     * <p>TAT is written as an integer string: Lua's default number-to-string
     * conversion loses precision at epoch-magnitude values.</p>
     */
    static final String GCRA_FUNCTION = """
            local function gcra_check(key, now_ms, window_ms, limit, cost)
              local now = now_ms * 1000
              local window = window_ms * 1000
              local interval = math.ceil(window / limit)
              local tolerance = window - interval
              if tolerance < 0 then
                tolerance = 0
              end

              local tat = tonumber(redis.call('GET', key))
              if tat == nil then
                tat = now
              end
              if tat < now then
                tat = now
              end

              local nextTat = tat + interval * cost
              local earliest = tat + (cost - 1) * interval
              if earliest > now + tolerance then
                local remaining = math.floor(
                    (now + tolerance - tat) / interval) + 1
                if remaining < 0 then remaining = 0 end
                if remaining > limit then remaining = limit end
                local retry = math.max(1,
                    math.ceil((earliest - now - tolerance) / 1000000))
                return 0, remaining, retry, nextTat, interval
              end

              local remaining = math.floor(
                  (now + tolerance - nextTat) / interval) + 1
              if remaining < 0 then remaining = 0 end
              if remaining > limit then remaining = limit end
              return 1, remaining, 0, nextTat, interval
            end
            """;

    /** ARGV: now(ms), window(ms), limit, cost. */
    public static final DefaultRedisScript<List> GCRA_SINGLE = new DefaultRedisScript<>(
            GCRA_FUNCTION + """
                    local windowMs = tonumber(ARGV[2])
                    local allowed, remaining, retry, newTat, interval =
                        gcra_check(KEYS[1], tonumber(ARGV[1]), windowMs,
                            tonumber(ARGV[3]), tonumber(ARGV[4]))
                    if allowed == 1 then
                      redis.call('SET', KEYS[1], string.format('%d', newTat),
                          'PX', string.format('%d',
                              math.ceil(windowMs + interval / 1000)))
                    end
                    return {allowed, remaining, retry}
                    """, List.class);

    /** ARGV: now(ms), window(ms), limit, member, cost. */
    public static final DefaultRedisScript<List> SORTED_SET_SINGLE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            local cost = tonumber(ARGV[5])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)

            if count + cost > limit then
              local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
              local retry = 1
              if oldest[2] ~= nil then
                retry = math.max(1,
                    math.ceil((tonumber(oldest[2]) + window - now) / 1000))
              end
              return {0, math.max(0, limit - count), retry}
            end

            for i = 1, cost do
              redis.call('ZADD', key, now, member .. ':' .. i)
            end
            redis.call('PEXPIRE', key, window)
            return {1, limit - count - cost, 0}
            """, List.class);

    /**
     * Combined admission check.
     *
     * <p>KEYS: blacklist, trust, quota, abuse.
     * ARGV: now(ms), window(ms), highLimit, mediumLimit, lowLimit, enabled,
     * cost, abuseLimit.</p>
     *
     * <p>Returns {@code {allowed, tier, limit, remaining, retryAfter, reason}}
     * where reason is one of {@code OK}, {@code BLACKLISTED}, {@code QUOTA},
     * {@code ABUSE}.</p>
     */
    public static final DefaultRedisScript<List> GCRA_ADMISSION = new DefaultRedisScript<>(
            GCRA_FUNCTION + """
                    local blacklistKey = KEYS[1]
                    local trustKey = KEYS[2]
                    local quotaKey = KEYS[3]
                    local abuseKey = KEYS[4]

                    local now = tonumber(ARGV[1])
                    local window = tonumber(ARGV[2])
                    local limitHigh = tonumber(ARGV[3])
                    local limitMedium = tonumber(ARGV[4])
                    local limitLow = tonumber(ARGV[5])
                    local enabled = tonumber(ARGV[6])
                    local cost = tonumber(ARGV[7])
                    local abuseLimit = tonumber(ARGV[8])

                    if redis.call('EXISTS', blacklistKey) == 1 then
                      return {0, 'BLACKLISTED', 0, 0, 0, 'BLACKLISTED'}
                    end

                    local tier = redis.call('GET', trustKey)
                    if tier == false then
                      tier = 'MEDIUM'
                    end

                    local limit
                    if tier == 'HIGH' then
                      limit = limitHigh
                    elseif tier == 'LOW' then
                      limit = limitLow
                    elseif tier == 'BLACKLISTED' then
                      limit = 0
                    else
                      tier = 'MEDIUM'
                      limit = limitMedium
                    end

                    if enabled == 0 then
                      return {1, tier, limit, limit, 0, 'OK'}
                    end

                    if limit <= 0 then
                      return {0, tier, 0, 0, 60, 'QUOTA'}
                    end

                    local abuseNewTat
                    local abuseInterval
                    if abuseLimit > 0 then
                      local abuseAllowed, _, abuseRetry, newTat, interval =
                          gcra_check(abuseKey, now, window, abuseLimit, 1)
                      if abuseAllowed == 0 then
                        return {0, tier, abuseLimit, 0, abuseRetry, 'ABUSE'}
                      end
                      abuseNewTat = newTat
                      abuseInterval = interval
                    end

                    local allowed, remaining, retry, quotaNewTat, quotaInterval =
                        gcra_check(quotaKey, now, window, limit, cost)
                    if allowed == 0 then
                      return {0, tier, limit, remaining, retry, 'QUOTA'}
                    end

                    redis.call('SET', quotaKey,
                        string.format('%d', quotaNewTat),
                        'PX', string.format('%d',
                            math.ceil(window + quotaInterval / 1000)))

                    if abuseLimit > 0 then
                      redis.call('SET', abuseKey,
                          string.format('%d', abuseNewTat),
                          'PX', string.format('%d',
                              math.ceil(window + abuseInterval / 1000)))
                    end

                    return {1, tier, limit, remaining, 0, 'OK'}
                    """, List.class);

    /**
     * Sorted-set fallback with the same admission contract.
     *
     * <p>KEYS: blacklist, trust, quota, abuse.
     * ARGV: now(ms), window(ms), highLimit, mediumLimit, lowLimit, enabled,
     * member, cost, abuseLimit.</p>
     */
    public static final DefaultRedisScript<List> SORTED_SET_ADMISSION = new DefaultRedisScript<>("""
            local blacklistKey = KEYS[1]
            local trustKey = KEYS[2]
            local quotaKey = KEYS[3]
            local abuseKey = KEYS[4]

            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limitHigh = tonumber(ARGV[3])
            local limitMedium = tonumber(ARGV[4])
            local limitLow = tonumber(ARGV[5])
            local enabled = tonumber(ARGV[6])
            local member = ARGV[7]
            local cost = tonumber(ARGV[8])
            local abuseLimit = tonumber(ARGV[9])

            if redis.call('EXISTS', blacklistKey) == 1 then
              return {0, 'BLACKLISTED', 0, 0, 0, 'BLACKLISTED'}
            end

            local tier = redis.call('GET', trustKey)
            if tier == false then
              tier = 'MEDIUM'
            end

            local limit
            if tier == 'HIGH' then
              limit = limitHigh
            elseif tier == 'LOW' then
              limit = limitLow
            elseif tier == 'BLACKLISTED' then
              limit = 0
            else
              tier = 'MEDIUM'
              limit = limitMedium
            end

            if enabled == 0 then
              return {1, tier, limit, limit, 0, 'OK'}
            end

            if limit <= 0 then
              return {0, tier, 0, 0, 60, 'QUOTA'}
            end

            if abuseLimit > 0 then
              redis.call('ZREMRANGEBYSCORE', abuseKey, '-inf', now - window)
              local abuseCount = redis.call('ZCARD', abuseKey)
              if abuseCount >= abuseLimit then
                local oldest = redis.call('ZRANGE', abuseKey, 0, 0, 'WITHSCORES')
                local retry = 1
                if oldest[2] ~= nil then
                  retry = math.max(1,
                      math.ceil((tonumber(oldest[2]) + window - now) / 1000))
                end
                return {0, tier, abuseLimit, 0, retry, 'ABUSE'}
              end
            end

            redis.call('ZREMRANGEBYSCORE', quotaKey, '-inf', now - window)
            local count = redis.call('ZCARD', quotaKey)
            if count + cost > limit then
              local oldest = redis.call('ZRANGE', quotaKey, 0, 0, 'WITHSCORES')
              local retry = 1
              if oldest[2] ~= nil then
                retry = math.max(1,
                    math.ceil((tonumber(oldest[2]) + window - now) / 1000))
              end
              return {0, tier, limit, math.max(0, limit - count), retry, 'QUOTA'}
            end

            for i = 1, cost do
              redis.call('ZADD', quotaKey, now, member .. ':' .. i)
            end
            redis.call('PEXPIRE', quotaKey, window)

            if abuseLimit > 0 then
              redis.call('ZADD', abuseKey, now, member)
              redis.call('PEXPIRE', abuseKey, window)
            end

            return {1, tier, limit, limit - count - cost, 0, 'OK'}
            """, List.class);
}
