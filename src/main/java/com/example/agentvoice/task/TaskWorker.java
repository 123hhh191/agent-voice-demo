package com.example.agentvoice.task;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded worker executor; task polling/execution is submitted here, never on request/event threads. */
@Component
public class TaskWorker {
    private final ThreadPoolExecutor executor;
    public TaskWorker(@Value("${app.tasks.workers:4}") int workers, @Value("${app.tasks.queue-capacity:64}") int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) throw new IllegalArgumentException("worker bounds must be positive");
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), r -> {
            Thread thread = new Thread(r, "agent-task-worker"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public void submit(Runnable task) { executor.execute(task); }
    /** Allows voice timeout/cancellation to interrupt a queued or running finishInput. */
    public java.util.concurrent.Future<?> submitCancellable(Runnable task) { return executor.submit(task); }
    @PreDestroy public void close() { executor.shutdown(); }
}
