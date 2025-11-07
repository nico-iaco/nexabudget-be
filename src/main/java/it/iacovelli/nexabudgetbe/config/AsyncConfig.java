package it.iacovelli.nexabudgetbe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${async.executor.core-pool-size:2}")
    private int corePoolSize;

    @Value("${async.executor.max-pool-size:3}")
    private int maxPoolSize;

    @Value("${async.executor.queue-capacity:50}")
    private int queueCapacity;

    @Value("${async.executor.thread-name-prefix:async-transaction-sync-}")
    private String threadNamePrefix;

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.initialize();

        logger.info("Thread pool asincrono configurato - Core: {}, Max: {}, Queue: {}",
                corePoolSize, maxPoolSize, queueCapacity);

        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, obj) ->
                logger.error("Errore nell'esecuzione asincrona del metodo: {} con parametri: {}",
                        method.getName(), obj, throwable);
    }
}

