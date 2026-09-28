package com.example.agentvoice.event;

import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.common.UserIdentity;
import com.example.agentvoice.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RestController
@org.springframework.context.annotation.Profile("!scaffold")
public class RunEventController {
    private final SessionService sessions; private final RunEventService events; private final UserIdentity identity;
    private final Executor executor = new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r -> { Thread t = new Thread(r,"run-event-stream"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
    public RunEventController(SessionService sessions, RunEventService events, UserIdentity identity) { this.sessions=sessions;this.events=events;this.identity=identity; }

    @GetMapping(value="/api/runs/{runId}/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId, @RequestParam(defaultValue="0") long afterSeq, HttpServletRequest request) {
        if (afterSeq < 0) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","afterSeq 必须大于等于 0");
        if (!sessions.ownsRun(runId,identity.user(request))) throw new ApiException(HttpStatus.NOT_FOUND,"RUN_NOT_FOUND","运行记录不存在");
        SseEmitter emitter = new SseEmitter(60_000L);
        executor.execute(() -> {
            long cursor = afterSeq; long stopAt = System.currentTimeMillis()+55_000;
            try {
                while (System.currentTimeMillis()<stopAt) {
                    List<java.util.Map<String,Object>> batch=events.after(runId,cursor,100);
                    for (var event:batch) { cursor=((Number)event.get("event_seq")).longValue(); emitter.send(SseEmitter.event().id(event.get("event_id").toString()).name(event.get("type").toString()).data(event,MediaType.APPLICATION_JSON)); }
                    if (batch.isEmpty()) Thread.sleep(500);
                }
                emitter.complete();
            } catch (Exception ex) { emitter.completeWithError(ex); }
        });
        return emitter;
    }
}
