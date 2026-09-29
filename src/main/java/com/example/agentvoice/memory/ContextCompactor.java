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
    /** 将较早的合格对话压缩为摘要，保留最近轮次原文。 */
    public void compact(String sessionId,String apiKey){
        List<Map<String,Object>> turns=jdbc.queryForList("SELECT m.run_id,MAX(m.seq) max_seq FROM agent_message m JOIN agent_run r ON r.id=m.run_id WHERE m.session_id=? AND "+ModelHistoryFilter.ELIGIBLE_RUN+" GROUP BY m.run_id ORDER BY max_seq DESC",sessionId);
        if(turns.size()<=8)return;
        // 超过 8 轮才压缩；截止于第 7 新轮次末尾，最近 6 轮仍保留原文。
        long cutoff=((Number)turns.get(6).get("max_seq")).longValue();
        var excluded=ModelHistoryFilter.excluded(jdbc,sessionId);
        var old=ModelHistoryFilter.messages(jdbc,sessionId,0,cutoff);
        String source;
        try{source="来源消息序号与记录："+mapper.writeValueAsString(old);}catch(Exception e){throw new IllegalStateException(e);}
        var existing=jdbc.queryForList("SELECT summary_json,covered_through_seq FROM agent_summary WHERE session_id=? ORDER BY version DESC LIMIT 1",sessionId);
        String previous="";
        if(!existing.isEmpty())try{
            var saved=existing.get(0);var node=mapper.readTree(saved.get("summary_json").toString());
            long savedCoverage=((Number)saved.get("covered_through_seq")).longValue();
            if(ModelHistoryFilter.safeSummary(node,savedCoverage,excluded)){
                if(savedCoverage==cutoff)return;
                previous="已有摘要："+ModelHistoryFilter.promptSummary(node)+"\n";
            }
        }catch(java.io.IOException ex){throw new IllegalStateException("已保存摘要数据损坏",ex);}
        String instruction;
        try{instruction=new String(getClass().getResourceAsStream("/prompts/context-summary.md").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}
        String summary=llm.summarize(apiKey,List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",previous+source)));
        try{
            // 只接受约定结构完整的摘要，避免模型输出污染后续上下文。
            var node=mapper.readTree(summary);
            for(String key:List.of("facts","entities","pending","constraints")) if(!node.has(key)) return;
            if(node.size()!=4)return;
            ModelHistoryFilter.markSummary(mapper,(com.fasterxml.jackson.databind.node.ObjectNode)node,cutoff,excluded);
            summary=node.toString();
        }catch(Exception e){return;}
        Long version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM agent_summary WHERE session_id=?",Long.class,sessionId);
        jdbc.update("INSERT INTO agent_summary(session_id,version,covered_through_seq,summary_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE summary_json=VALUES(summary_json),version=VALUES(version)",sessionId,version,cutoff,summary);
        jdbc.update("UPDATE agent_session SET context_version=? WHERE id=? AND context_version<?",version,sessionId,version);
    }
}
