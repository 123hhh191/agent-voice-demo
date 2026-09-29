package com.example.agentvoice.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
@org.springframework.context.annotation.Profile("!scaffold")
public class OutboxDispatcher {
    private final JdbcTemplate jdbc;
    public OutboxDispatcher(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:500}")
    @Transactional
    /** 批量读取待派发事件并持久化为运行事件。 */
    public void dispatch() {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,aggregate_id,type,payload FROM outbox_event WHERE state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(6) ORDER BY created_at LIMIT 32");
        for (Map<String,Object> row : rows) dispatchOne(row);
    }
    /** 幂等派发单条 outbox 记录，失败时安排稍后重试。 */
    protected void dispatchOne(Map<String,Object> row) {
        String id = row.get("id").toString();
        int inserted = jdbc.update("INSERT IGNORE INTO run_event(run_id,event_seq,event_id,type,payload) SELECT ?,COALESCE(MAX(event_seq),0)+1,?,?,? FROM run_event WHERE run_id=?",
                row.get("aggregate_id"),id,row.get("type"),row.get("payload"),row.get("aggregate_id"));
        // A duplicate delivery is harmless; event_id is unique and the event remains persisted.
        if (inserted > 0 || !jdbc.queryForList("SELECT event_id FROM run_event WHERE event_id=?", id).isEmpty()) {
            jdbc.update("UPDATE outbox_event SET state='SENT',attempt_count=attempt_count+1 WHERE id=? AND state='PENDING'",id);
        } else {
            jdbc.update("UPDATE outbox_event SET attempt_count=attempt_count+1,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",id);
        }
    }
}
