package com.example.agentvoice.session;

import com.example.agentvoice.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class SessionService {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public SessionService(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public String create(String userId){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO agent_session(id,user_id,status) VALUES(?,?,'ACTIVE')",id,userId);return id;}
    public boolean owns(String sessionId,String userId){return jdbc.queryForList("SELECT id FROM agent_session WHERE id=? AND user_id=?",sessionId,userId).size()>0;}
    @Transactional
    public Run start(String sessionId,String userId,String requestId,String text,String traceId){
        var session=jdbc.query("SELECT active_run_id FROM agent_session WHERE id=? AND user_id=? FOR UPDATE",(rs,n)->rs.getString(1),sessionId,userId);
        if(session.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"SESSION_NOT_FOUND","会话不存在");
        var found=jdbc.query("SELECT id,status,answer,error_code,trace_id FROM agent_run WHERE session_id=? AND request_id=?",(rs,n)->new Run(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),sessionId,requestId);
        if(!found.isEmpty()) {
            Run prior=found.get(0);
            String priorText=jdbc.queryForObject("SELECT content FROM agent_message WHERE run_id=? AND role='user' ORDER BY seq LIMIT 1",String.class,prior.runId());
            if(!java.util.Objects.equals(priorText,text))throw new ApiException(HttpStatus.CONFLICT,"REQUEST_ID_REUSED","requestId 已用于不同输入");
            return "RUNNING".equals(prior.status()) ? new Run(prior.runId(),"IN_PROGRESS",prior.answer(),prior.errorCode(),prior.traceId()) : prior;
        }
        if(session.get(0)!=null)throw new ApiException(HttpStatus.CONFLICT,"SESSION_BUSY","会话正在处理其他请求");
        String runId=UUID.randomUUID().toString();
        jdbc.update("UPDATE agent_session SET active_run_id=? WHERE id=? AND user_id=? AND active_run_id IS NULL",runId,sessionId,userId);
        jdbc.update("INSERT INTO agent_run(id,session_id,request_id,status,trace_id,deadline_at) VALUES(?,?,?,'RUNNING',?,?)",runId,sessionId,requestId,traceId,Timestamp.from(Instant.now().plusSeconds(60)));
        long seq=nextSeq(sessionId); jdbc.update("INSERT INTO agent_message(session_id,run_id,seq,role,content) VALUES(?,?,?,'user',?)",sessionId,runId,seq,text);
        return new Run(runId,"RUNNING",null,null,traceId);
    }
    public long nextSeq(String sessionId){Long n=jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM agent_message WHERE session_id=?",Long.class,sessionId);return n==null?1:n;}
    public void append(String sessionId,String runId,String role,String content,String toolCallId,Object toolCalls){
        String json=null;if(toolCalls!=null)try{json=mapper.writeValueAsString(toolCalls);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
        jdbc.update("INSERT INTO agent_message(session_id,run_id,seq,role,content,tool_call_id,tool_calls_json) VALUES(?,?,?,?,?,?,?)",sessionId,runId,nextSeq(sessionId),role,content,toolCallId,json);
    }
    public List<Map<String,Object>> history(String sessionId,long afterSeq,int limit){return jdbc.queryForList("SELECT seq,role,content,tool_call_id,tool_calls_json,created_at FROM agent_message WHERE session_id=? AND seq>? ORDER BY seq LIMIT ?",sessionId,afterSeq,limit);}
    public List<Map<String,Object>> contextHistory(String sessionId){return jdbc.queryForList("SELECT role,content,tool_call_id,tool_calls_json FROM (SELECT * FROM agent_message WHERE session_id=? ORDER BY seq DESC LIMIT 30) m ORDER BY seq",sessionId);}
    @Transactional
    public void complete(String sessionId,String runId,String answer,int loops){int changed=jdbc.update("UPDATE agent_run SET status='SUCCEEDED',answer=?,loop_count=?,version=version+1 WHERE id=? AND status='RUNNING'",answer,loops,runId);if(changed==1)jdbc.update("UPDATE agent_session SET active_run_id=NULL WHERE id=? AND active_run_id=?",sessionId,runId);}
    @Transactional
    public void fail(String sessionId,String runId,String code,int loops){int changed=jdbc.update("UPDATE agent_run SET status='FAILED',error_code=?,loop_count=?,version=version+1 WHERE id=? AND status='RUNNING'",code,loops,runId);if(changed==1)jdbc.update("UPDATE agent_session SET active_run_id=NULL WHERE id=? AND active_run_id=?",sessionId,runId);}
    public Run getRun(String runId,String userId){var rows=jdbc.query("SELECT r.id,r.status,r.answer,r.error_code,r.trace_id FROM agent_run r JOIN agent_session s ON s.id=r.session_id WHERE r.id=? AND s.user_id=?",(rs,n)->new Run(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)),runId,userId);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"RUN_NOT_FOUND","运行记录不存在");return rows.get(0);}
    public boolean ownsRun(String runId,String userId){return !jdbc.queryForList("SELECT r.id FROM agent_run r JOIN agent_session s ON s.id=r.session_id WHERE r.id=? AND s.user_id=?",runId,userId).isEmpty();}
    public record Run(String runId,String status,String answer,String errorCode,String traceId){}
}
