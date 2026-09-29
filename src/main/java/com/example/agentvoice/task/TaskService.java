package com.example.agentvoice.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class TaskService {
    private final JdbcTemplate jdbc;
    public TaskService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    /** 为工具调用创建待执行任务。 */
    public String enqueue(String invocationId, Instant deadline) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO async_task(id,invocation_id,state,deadline_at) VALUES(?,?,'PENDING',?)",
                id, invocationId, Timestamp.from(deadline));
        return id;
    }

    /** Uses an atomic conditional UPDATE, compatible with MySQL versions without SKIP LOCKED. */
    @Transactional
    public ClaimedTask claim(String workerId, long leaseSeconds) {
        var ids = jdbc.query("SELECT id,invocation_id,owner_version FROM async_task WHERE state='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP(6)) AND deadline_at>CURRENT_TIMESTAMP(6) ORDER BY created_at LIMIT 16",
                (rs,n) -> new ClaimedTask(rs.getString(1), rs.getString(2), rs.getLong(3), workerId));
        // 先读候选，再用版本条件更新认领，避免依赖数据库 SKIP LOCKED。
        for (ClaimedTask candidate : ids) {
            int changed = jdbc.update("UPDATE async_task SET state='RUNNING',owner_id=?,owner_version=owner_version+1,attempt_count=attempt_count+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL ? SECOND) WHERE id=? AND state='PENDING' AND owner_version=?",
                    workerId, leaseSeconds, candidate.taskId(), candidate.ownerVersion());
            if (changed == 1) return new ClaimedTask(candidate.taskId(), candidate.invocationId(), candidate.ownerVersion()+1, workerId);
        }
        return null;
    }

    @Transactional
    /** 仅允许当前 worker 和租约版本完成任务。 */
    public boolean finish(ClaimedTask task, String state) {
        return jdbc.update("UPDATE async_task SET state=?,lease_until=NULL WHERE id=? AND state='RUNNING' AND owner_id=? AND owner_version=?",
                state, task.taskId(), task.ownerId(), task.ownerVersion()) == 1;
    }
    public record ClaimedTask(String taskId, String invocationId, long ownerVersion, String ownerId) { }
}
