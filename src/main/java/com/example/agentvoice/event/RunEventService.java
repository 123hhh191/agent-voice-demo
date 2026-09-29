package com.example.agentvoice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class RunEventService {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public RunEventService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Transactional
    /** 直接追加运行事件并返回事件序号。 */
    public long append(String runId, String type, Object payload) {
        try {
            String eventId = UUID.randomUUID().toString();
            Long seq = jdbc.queryForObject("SELECT COALESCE(MAX(event_seq),0)+1 FROM run_event WHERE run_id=? FOR UPDATE", Long.class, runId);
            long next = seq == null ? 1 : seq;
            jdbc.update("INSERT INTO run_event(run_id,event_seq,event_id,type,payload) VALUES(?,?,?,?,?)", runId,next,eventId,type,mapper.writeValueAsString(payload));
            return next;
        } catch (Exception ex) { throw new IllegalStateException("Cannot persist run event", ex); }
    }
    @Transactional
    /** 将事件写入 outbox，等待异步派发。 */
    public String enqueue(String runId, String type, Object payload) {
        try {
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO outbox_event(id,aggregate_id,type,payload) VALUES(?,?,?,?)",id,runId,type,mapper.writeValueAsString(payload));
            return id;
        } catch (Exception ex) { throw new IllegalStateException("Cannot enqueue run event",ex); }
    }
    /** 查询指定序号之后的运行事件。 */
    public List<Map<String,Object>> after(String runId, long afterSeq, int limit) {
        return jdbc.queryForList("SELECT run_id,event_seq,event_id,type,payload,created_at FROM run_event WHERE run_id=? AND event_seq>? ORDER BY event_seq LIMIT ?",runId,afterSeq,limit);
    }
}
