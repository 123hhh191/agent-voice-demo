package com.example.agentvoice.memory;

import com.example.agentvoice.llm.DeepSeekClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@org.springframework.context.annotation.Profile("!scaffold")
public class ContextCompactor {
    private final JdbcTemplate jdbc; private final DeepSeekClient llm; private final ObjectMapper mapper;
    public ContextCompactor(JdbcTemplate jdbc,DeepSeekClient llm,ObjectMapper mapper){this.jdbc=jdbc;this.llm=llm;this.mapper=mapper;}
    public void compact(String sessionId){
        List<Map<String,Object>> turns=jdbc.queryForList("SELECT run_id,MAX(seq) max_seq FROM agent_message WHERE session_id=? GROUP BY run_id ORDER BY max_seq DESC",sessionId);
        if(turns.size()<=8)return;
        var retained=turns.subList(0,6);long cutoff=((Number)turns.get(6).get("max_seq")).longValue();
        var old=jdbc.queryForList("SELECT seq,role,content,tool_call_id,tool_calls_json FROM agent_message WHERE session_id=? AND seq<=? ORDER BY seq",sessionId,cutoff);
        String source;
        try{source="来源消息序号与记录："+mapper.writeValueAsString(old);}catch(Exception e){throw new IllegalStateException(e);}
        var existing=jdbc.queryForList("SELECT summary_json FROM agent_summary WHERE session_id=? ORDER BY version DESC LIMIT 1",sessionId);
        String instruction;
        try{instruction=new String(getClass().getResourceAsStream("/prompts/context-summary.md").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}
        String summary=llm.summarize(List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",(existing.isEmpty()?"": "已有摘要："+existing.get(0).get("summary_json")+"\n")+source)));
        try{
            var node=mapper.readTree(summary);
            for(String key:List.of("facts","entities","pending","constraints")) if(!node.has(key)) return;
            if(node.size()!=4)return;
        }catch(Exception e){return;}
        Long version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM agent_summary WHERE session_id=?",Long.class,sessionId);
        jdbc.update("INSERT INTO agent_summary(session_id,version,covered_through_seq,summary_json) VALUES(?,?,?,?)",sessionId,version,cutoff,summary);
        jdbc.update("UPDATE agent_session SET context_version=? WHERE id=? AND context_version<?",version,sessionId,version);
    }
}
