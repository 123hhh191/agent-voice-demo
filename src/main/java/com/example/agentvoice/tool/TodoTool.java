package com.example.agentvoice.tool;

import com.example.agentvoice.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@org.springframework.context.annotation.Profile("!scaffold")
public class TodoTool {
    private final JdbcTemplate jdbc;
    public TodoTool(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Object create(JsonNode a, ToolExecutionContext c) {
        String title = a.path("title").asText("").trim(); validateTitle(title);
        String id = UUID.randomUUID().toString(); OffsetDateTime due = parseDue(a.get("dueAt"));
        jdbc.update("INSERT INTO todo_item(id,user_id,session_id,title,due_at,status,operation_id) VALUES(?,?,?,?,?,'PENDING',?)",
                id,c.userId(),c.sessionId(),title,due == null ? null : Timestamp.from(due.toInstant()),c.operationId());
        return Map.of("todoId", id, "title", title, "status", "PENDING", "version", 0);
    }
    public Object list(JsonNode a, ToolExecutionContext c) {
        String status = a.path("status").asText("ALL");
        if (!List.of("PENDING","COMPLETED","ALL").contains(status)) throw invalid();
        String sql = "SELECT id,title,due_at,status,version FROM todo_item WHERE user_id=? AND session_id=?" + (status.equals("ALL") ? "" : " AND status=?") + " ORDER BY created_at DESC LIMIT 100";
        List<Map<String,Object>> rows = status.equals("ALL") ? jdbc.queryForList(sql,c.userId(),c.sessionId()) : jdbc.queryForList(sql,c.userId(),c.sessionId(),status);
        return Map.of("items", rows);
    }
    public Object update(JsonNode a, ToolExecutionContext c) {
        String id = a.path("todoId").asText(""); long version = a.path("expectedVersion").asLong(-1);
        boolean hasTitle = a.has("title"), hasDue = a.has("dueAt"), hasStatus = a.has("status");
        if (id.isBlank() || version < 0 || !(hasTitle || hasDue || hasStatus)) throw invalid();
        String title = hasTitle ? a.path("title").asText("").trim() : null;
        if (hasTitle) validateTitle(title);
        String status = hasStatus ? a.path("status").asText("") : null;
        if (hasStatus && !List.of("PENDING","COMPLETED").contains(status)) throw invalid();
        OffsetDateTime due = hasDue ? parseDue(a.get("dueAt")) : null;
        int changed = jdbc.update("UPDATE todo_item SET title=COALESCE(?,title), due_at=IF(?, ?, due_at), status=COALESCE(?,status), version=version+1 WHERE id=? AND user_id=? AND session_id=? AND version=?",
                title,hasDue,due == null ? null : Timestamp.from(due.toInstant()),status,id,c.userId(),c.sessionId(),version);
        if (changed == 0) throw new ApiException(HttpStatus.CONFLICT,"TODO_VERSION_CONFLICT","待办不存在、无权访问或版本已变化");
        return Map.of("todoId",id,"version",version+1,"updated",true);
    }
    private void validateTitle(String title) { if (title.isBlank() || title.length()>240) throw invalid(); }
    private OffsetDateTime parseDue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalid();
        try { return OffsetDateTime.parse(node.asText()); } catch (Exception ex) { throw invalid(); }
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TOOL_ARGUMENTS","待办参数无效"); }
}
