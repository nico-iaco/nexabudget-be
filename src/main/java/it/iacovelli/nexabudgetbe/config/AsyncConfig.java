package it.iacovelli.nexabudgetbe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@org.springframework.retry.annotation.EnableRetry
@org.springframework.scheduling.annotation.EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${async.executor.thread-name-prefix:async-transaction-sync-}")
    private String threadNamePrefix;

    @Override
    public Executor getAsyncExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        executor.setThreadNamePrefix(threadNamePrefix);

        logger.info("Executor asincrono configurato con Virtual Threads - Prefix: {}", threadNamePrefix);

        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, obj) ->
                logger.error("Errore nell'esecuzione asincrona del metodo: {} con parametri: {}",
                        method.getName(), obj, throwable);
    }
}
