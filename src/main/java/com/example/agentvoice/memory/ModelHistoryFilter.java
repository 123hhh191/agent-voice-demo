package com.example.agentvoice.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Shares the eligibility rule between live context and persisted summaries. */
final class ModelHistoryFilter {
    private static final String EXCLUDED_IDS = "_excludedCredentialFailureRuns";
    // Retain failed runs with any model/tool evidence: writes may already have happened.
    static final String EXCLUDED_RUN = """
            r.status='FAILED'
            AND COALESCE(r.error_code,'') IN ('DEEPSEEK_API_KEY_REQUIRED','DEEPSEEK_API_KEY_INVALID')
            AND r.loop_count<=1
            AND NOT EXISTS (SELECT 1 FROM agent_message evidence
                            WHERE evidence.run_id=r.id AND evidence.role IN ('assistant','tool'))
            AND NOT EXISTS (SELECT 1 FROM tool_invocation invocation WHERE invocation.run_id=r.id)
            """;
    static final String ELIGIBLE_RUN = "NOT (" + EXCLUDED_RUN + ")";

    private ModelHistoryFilter() { }

    static List<Map<String,Object>> excluded(JdbcTemplate jdbc, String sessionId) {
        return jdbc.queryForList("SELECT r.id run_id,MIN(m.seq) first_seq FROM agent_run r "
                + "JOIN agent_message m ON m.run_id=r.id WHERE r.session_id=? AND " + EXCLUDED_RUN
                + " GROUP BY r.id", sessionId);
    }

    static List<Map<String,Object>> messages(JdbcTemplate jdbc, String sessionId, long after, long through) {
        return jdbc.queryForList("SELECT m.seq,m.run_id,m.role,m.content,m.tool_call_id,m.tool_calls_json "
                + "FROM agent_message m JOIN agent_run r ON r.id=m.run_id "
                + "WHERE m.session_id=? AND m.seq>? AND m.seq<=? AND " + ELIGIBLE_RUN + " ORDER BY m.seq",
                sessionId, after, through);
    }

    static boolean safeSummary(JsonNode summary, long covered, List<Map<String,Object>> excluded) {
        Set<String> recorded = new java.util.HashSet<>();
        summary.path(EXCLUDED_IDS).forEach(id -> recorded.add(id.asText()));
        return excluded.stream().filter(row -> ((Number)row.get("first_seq")).longValue() <= covered)
                .allMatch(row -> recorded.contains(row.get("run_id").toString()));
    }

    static String promptSummary(JsonNode summary) {
        ObjectNode clean = ((ObjectNode) summary).deepCopy();
        clean.remove(EXCLUDED_IDS);
        return clean.toString();
    }

    static void markSummary(ObjectMapper mapper, ObjectNode summary, long covered, List<Map<String,Object>> excluded) {
        var ids = excluded.stream().filter(row -> ((Number)row.get("first_seq")).longValue() <= covered)
                .map(row -> row.get("run_id").toString()).collect(Collectors.toList());
        summary.set(EXCLUDED_IDS, mapper.valueToTree(ids));
    }
}
