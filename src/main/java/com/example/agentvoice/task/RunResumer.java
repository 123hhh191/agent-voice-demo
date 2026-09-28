package com.example.agentvoice.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically guards resumption until all invocations belonging to the run are terminal. */
@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class RunResumer {
    private final JdbcTemplate jdbc;
    public RunResumer(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public boolean claimResume(String runId) {
        Integer unfinished = jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation WHERE run_id=? AND state IN ('PREPARED','RUNNING')", Integer.class, runId);
        if (unfinished != null && unfinished > 0) return false;
        return jdbc.update("UPDATE agent_run SET status='RUNNING',version=version+1 WHERE id=? AND status='WAITING_TOOL'", runId) == 1;
    }
}
