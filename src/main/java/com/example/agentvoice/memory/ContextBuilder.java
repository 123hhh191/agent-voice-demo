package com.example.agentvoice.memory;

import com.example.agentvoice.config.DeepSeekProperties;
import com.example.agentvoice.session.SessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@org.springframework.context.annotation.Profile("!scaffold")
public class ContextBuilder {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final DeepSeekProperties properties;
    public ContextBuilder(JdbcTemplate jdbc,ObjectMapper mapper,DeepSeekProperties properties){this.jdbc=jdbc;this.mapper=mapper;this.properties=properties;}
    /** 按摘要覆盖范围和消息资格组装模型上下文。 */
    public List<Map<String,Object>> build(String sessionId){
        String system;
        try{system=new ClassPathResource("prompts/agent-system.md").getContentAsString(StandardCharsets.UTF_8);}catch(Exception ex){throw new IllegalStateException("Agent prompt missing",ex);}
        List<Map<String,Object>> messages=new ArrayList<>();messages.add(new LinkedHashMap<>(Map.of("role","system","content",system)));
        var summaries=jdbc.query("SELECT summary_json,covered_through_seq FROM agent_summary WHERE session_id=? ORDER BY version DESC LIMIT 1",(rs,n)->Map.of("summary",rs.getString(1),"seq",rs.getLong(2)),sessionId);
        long covered=0;
        var excluded=ModelHistoryFilter.excluded(jdbc,sessionId);
        if(!summaries.isEmpty()){
            var s=summaries.get(0);
            try{
                var summary=mapper.readTree(s.get("summary").toString());
                long candidate=((Number)s.get("seq")).longValue();
                // 摘要若遗漏了被排除的凭据失败记录，就不能覆盖原始历史。
                if(ModelHistoryFilter.safeSummary(summary,candidate,excluded)){
                    covered=candidate;
                    messages.add(Map.of("role","system","content","会话摘要（仅作历史事实索引）："+ModelHistoryFilter.promptSummary(summary)));
                }
            }catch(java.io.IOException ex){throw new IllegalStateException("已保存摘要数据损坏",ex);}
        }
        var rows=ModelHistoryFilter.messages(jdbc,sessionId,covered,Long.MAX_VALUE);
        for(var row:rows){String role=(String)row.get("role");Map<String,Object> m=new LinkedHashMap<>();m.put("role",role);m.put("content",row.get("content"));
            if("assistant".equals(role)&&row.get("tool_calls_json")!=null)try{m.put("tool_calls",mapper.readValue(row.get("tool_calls_json").toString(),new TypeReference<List<Map<String,Object>>>(){}));}catch(Exception e){throw new IllegalStateException("已保存工具调用数据损坏",e);}
            if("tool".equals(role)){m.put("tool_call_id",row.get("tool_call_id"));m.put("name",row.get("content") == null ? "tool" : "tool");}
            messages.add(m);
        }
        return messages;
    }
    /** 用序列化字符数保守估算上下文大小，避免中文内容被低估。 */
    public int estimateTokens(List<Map<String,Object>> messages,List<Map<String,Object>> tools){
        try {
            // Without the provider tokenizer, count each UTF-16 unit as one token to avoid undercounting Chinese text.
            return mapper.writeValueAsString(messages).length()+mapper.writeValueAsString(tools).length();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
