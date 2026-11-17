package com.fintech.fintech_gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class RuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Bounded executor for best-effort trust-tier writes.
     *
     * <p>It is deliberately separate from the audit batch writer: a slow
     * trust-history insert must not delay security evidence, and the audit
     * writer must never run on a request thread.</p>
     *
     * <p>Rejection is counted and logged, then the task is dropped. Trust
     * updates are derived state that the next successful request can
     * re-establish; running the task on the caller would put a Redis or
     * database call back on the Tomcat thread, which is exactly what this
     * phase removes.</p>
     */
    @Bean(name = "trustExecutor")
    public Executor trustExecutor(MeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("trust-");
        executor.setRejectedExecutionHandler(dropWithMetric(registry));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    private RejectedExecutionHandler dropWithMetric(MeterRegistry registry) {
        return (task, pool) -> {
            registry.counter("gateway.trust.executor.rejected").increment();
            log.error("Trust executor is saturated (active={}, queued={}); "
                            + "dropping a best-effort trust update",
                    pool.getActiveCount(), pool.getQueue().size());
        };
    }
}
