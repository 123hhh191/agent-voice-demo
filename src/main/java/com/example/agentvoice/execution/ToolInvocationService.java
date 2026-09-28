package com.example.agentvoice.execution;

import com.example.agentvoice.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class ToolInvocationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ToolInvocationService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional
    public Invocation prepare(String runId, String toolCallId, String operationId, String toolName, String argsJson) {
        try {
            JsonNode parsed = mapper.readTree(argsJson);
            if (!parsed.isObject()) throw new IllegalArgumentException();
            TreeMap<String,Object> sorted = mapper.convertValue(parsed, mapper.getTypeFactory().constructMapType(TreeMap.class, String.class, Object.class));
            String stableJson = mapper.writeValueAsString(sorted);
            String hash = sha256(stableJson);
            var existing = jdbc.query("SELECT id,args_hash,args_json,state,result_json FROM tool_invocation WHERE operation_id=?",
                    (rs,n) -> new Invocation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), operationId);
            if (!existing.isEmpty()) {
                Invocation old = existing.get(0);
                if (!hash.equals(old.argsHash())) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同一 operationId 对应了不同参数");
                return old;
            }
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO tool_invocation(id,run_id,tool_call_id,operation_id,tool_name,args_json,args_hash,state) VALUES(?,?,?,?,?,?,?,'PREPARED')",
                    id,runId,toolCallId,operationId,toolName,stableJson,hash);
            return new Invocation(id, hash, stableJson, "PREPARED", null);
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TOOL_ARGUMENTS","工具参数无效"); }
    }

    @Transactional
    public boolean claim(String invocationId, long expectedVersion) {
        return jdbc.update("UPDATE tool_invocation SET state='RUNNING',owner_version=owner_version+1 WHERE id=? AND state IN ('PREPARED','RUNNING') AND owner_version=?", invocationId, expectedVersion) == 1;
    }

    @Transactional
    public boolean complete(String invocationId, long ownerVersion, String state, Object result) {
        if (!java.util.List.of("SUCCEEDED","FAILED","UNKNOWN").contains(state)) throw new IllegalArgumentException("terminal invocation state required");
        try {
            return jdbc.update("UPDATE tool_invocation SET state=?,result_json=? WHERE id=? AND state='RUNNING' AND owner_version=?",
                    state, result == null ? null : mapper.writeValueAsString(result), invocationId, ownerVersion) == 1;
        } catch (Exception ex) { throw new IllegalStateException("Cannot serialize invocation result", ex); }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    public record Invocation(String id, String argsHash, String argsJson, String state, String resultJson) { }
}
