package com.example.agentvoice.memory;

import com.example.agentvoice.agent.AgentLoop;
import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.config.DeepSeekProperties;
import com.example.agentvoice.execution.ToolInvocationService;
import com.example.agentvoice.llm.DeepSeekClient;
import com.example.agentvoice.llm.LlmDecision;
import com.example.agentvoice.session.SessionService;
import com.example.agentvoice.tool.ToolExecutionContext;
import com.example.agentvoice.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses only rollback-isolated fixtures in the configured development MySQL schema. */
@SpringBootTest
@Transactional
class CredentialHistoryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService sessions;
    @Autowired ContextBuilder context;
    @Autowired ContextCompactor compactor;
    @Autowired AgentLoop loop;
    @Autowired ToolInvocationService invocations;
    @Autowired ToolRegistry tools;
    @Autowired DeepSeekProperties properties;
    @MockitoBean DeepSeekClient llm;
    String sessionId;String user;

    @BeforeEach void createSession(){
        assertEquals("agent_voice_demo",jdbc.queryForObject("SELECT DATABASE()",String.class),"Tests require the dedicated development schema");
        user="it-key-"+UUID.randomUUID();sessionId=sessions.create(user);
    }

    @Test void correctedKeyDoesNotReplayRejectedIntent(){
        when(llm.decide(eq("review-rejected"),anyList(),anyList())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,"DEEPSEEK_API_KEY_INVALID","Key invalid"));
        assertThrows(ApiException.class,()->loop.run(sessionId,user,"first","rejected-write-intent","review-rejected","trace"));
        when(llm.decide(eq("review-corrected"),anyList(),anyList())).thenAnswer(call->{
            assertFalse(call.getArgument(1).toString().contains("rejected-write-intent"));
            return new LlmDecision("ok","stop",List.of(),null);
        });
        assertEquals("SUCCEEDED",loop.run(sessionId,user,"second","current-question","review-corrected","trace").status());
        assertTrue(sessions.history(sessionId,0,100).toString().contains("rejected-write-intent"),"Display/audit history must remain available");
    }

    @Test void sameUserSessionsKeepHistoryAndTodosIsolated(){
        String otherSession=sessions.create(user);
        var first=sessions.start(sessionId,user,"first","查天气并记待办","trace");
        sessions.append(sessionId,first.runId(),"assistant","东京天气是演示数据",null,null);
        sessions.complete(sessionId,first.runId(),"完成",1);
        var second=sessions.start(otherSession,user,"second","写周报并记待办","trace");
        sessions.append(otherSession,second.runId(),"assistant","周报草稿已保存",null,null);
        sessions.complete(otherSession,second.runId(),"完成",1);

        var firstContext=context.build(sessionId).toString();
        var secondContext=context.build(otherSession).toString();
        assertTrue(firstContext.contains("查天气并记待办"));
        assertFalse(firstContext.contains("写周报并记待办"));
        assertTrue(secondContext.contains("写周报并记待办"));
        assertFalse(secondContext.contains("查天气并记待办"));

        var firstToolContext=new ToolExecutionContext(user,sessionId,"op-1");
        var secondToolContext=new ToolExecutionContext(user,otherSession,"op-2");
        tools.execute("todo_create","{\"title\":\"带伞\",\"dueAt\":null}",firstToolContext);
        tools.execute("todo_create","{\"title\":\"提交周报\",\"dueAt\":null}",secondToolContext);
        String firstTodos=tools.execute("todo_list","{\"status\":\"ALL\"}",firstToolContext).toString();
        String secondTodos=tools.execute("todo_list","{\"status\":\"ALL\"}",secondToolContext).toString();
        assertTrue(firstTodos.contains("带伞"));assertFalse(firstTodos.contains("提交周报"));
        assertTrue(secondTodos.contains("提交周报"));assertFalse(secondTodos.contains("带伞"));
    }

    @Test void followUpUsesEarlierToolResultToUpdateTodo(){
        AtomicInteger decision=new AtomicInteger();
        when(llm.decide(eq("follow-up-key"),anyList(),anyList())).thenAnswer(call->{
            int step=decision.incrementAndGet();
            List<Map<String,Object>> messages=call.getArgument(1);
            if(step==1)return new LlmDecision(null,"tool_calls",List.of(new LlmDecision.ToolCall("create-call","todo_create","{\"title\":\"整理周报\",\"dueAt\":null}")),null);
            if(step==2)return new LlmDecision("已记下周报待办。","stop",List.of(),null);
            if(step==3){
                String history=messages.toString();
                assertTrue(history.contains("整理周报"),"Follow-up context should retain the previous todo result");
                Map<String,Object> item=jdbc.queryForMap("SELECT id FROM todo_item WHERE user_id=? AND session_id=? AND title=?",user,sessionId,"整理周报");
                String args="{\"todoId\":\""+item.get("id")+"\",\"expectedVersion\":0,\"title\":\"提交周报\"}";
                return new LlmDecision(null,"tool_calls",List.of(new LlmDecision.ToolCall("update-call","todo_update",args)),null);
            }
            return new LlmDecision("已把待办改为提交周报。","stop",List.of(),null);
        });

        assertEquals("SUCCEEDED",loop.run(sessionId,user,"turn-1","帮我记个周报待办","follow-up-key","trace").status());
        assertEquals("SUCCEEDED",loop.run(sessionId,user,"turn-2","把刚才的待办改成提交周报","follow-up-key","trace").status());
        assertEquals(4,decision.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM todo_item WHERE user_id=? AND session_id=? AND title=? AND version=1",Integer.class,user,sessionId,"提交周报"));
    }

    @Test void partialToolWorkAndOtherFailuresRemainInContext(){
        fail("exclude-this","DEEPSEEK_API_KEY_REQUIRED",1,false);
        fail("keep-model-failure","MODEL_UNAVAILABLE",1,false);
        fail("keep-partial-tool","DEEPSEEK_API_KEY_INVALID",2,true);
        var prepared=sessions.start(sessionId,user,UUID.randomUUID().toString(),"keep-prepared-operation","trace");
        invocations.prepare(prepared.runId(),"call",UUID.randomUUID().toString(),"calculator","{\"expression\":\"1+2\"}");
        sessions.fail(sessionId,prepared.runId(),"DEEPSEEK_API_KEY_INVALID",1);
        String built=context.build(sessionId).toString();
        assertFalse(built.contains("exclude-this"));
        assertTrue(built.contains("keep-model-failure"));assertTrue(built.contains("keep-partial-tool"));assertTrue(built.contains("keep-prepared-operation"));
    }

    @Test void summariesContainingRejectedIntentAreBypassed(){
        fail("rejected-write-intent","DEEPSEEK_API_KEY_INVALID",1,false);
        jdbc.update("INSERT INTO agent_summary(session_id,version,covered_through_seq,summary_json) VALUES(?,1,1,?)",sessionId,"{\"facts\":[\"rejected-write-intent\"],\"entities\":[],\"pending\":[],\"constraints\":[]}");
        assertFalse(context.build(sessionId).toString().contains("rejected-write-intent"));
    }

    @Test void compactionRebuildsSameCoverageAndRecordsItsExclusions(){
        fail("rejected-write-intent","DEEPSEEK_API_KEY_INVALID",1,false);
        for(int i=0;i<9;i++){
            var run=sessions.start(sessionId,user,UUID.randomUUID().toString(),"accepted-"+i,"trace");
            sessions.append(sessionId,run.runId(),"assistant","answer-"+i,null,null);
            sessions.complete(sessionId,run.runId(),"answer-"+i,1);
        }
        // Seven messages covers the first rejected request and the oldest three valid runs.
        jdbc.update("INSERT INTO agent_summary(session_id,version,covered_through_seq,summary_json) VALUES(?,1,7,?)",sessionId,"{\"facts\":[\"rejected-write-intent\"],\"entities\":[],\"pending\":[],\"constraints\":[]}");
        when(llm.summarize(eq("review-key"),anyList())).thenAnswer(call->{
            assertFalse(call.getArgument(1).toString().contains("rejected-write-intent"));
            assertTrue(call.getArgument(1).toString().contains("accepted-0"));
            return "{\"facts\":[\"safe-summary\"],\"entities\":[],\"pending\":[],\"constraints\":[]}";
        });
        compactor.compact(sessionId,"review-key");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_summary WHERE session_id=?",Integer.class,sessionId));
        assertEquals(2L,jdbc.queryForObject("SELECT version FROM agent_summary WHERE session_id=?",Long.class,sessionId));
        String built=context.build(sessionId).toString();
        assertTrue(built.contains("safe-summary"));assertFalse(built.contains("rejected-write-intent"));
        assertFalse(built.contains("_excludedCredentialFailureRuns"),"Internal metadata must not be sent to the model");
        compactor.compact(sessionId,"review-key");
        verify(llm,times(1)).summarize(eq("review-key"),anyList());
    }

    @Test void bypassedLegacySummaryDoesNotStrandLongSession(){
        fail("rejected-write-intent","DEEPSEEK_API_KEY_INVALID",1,false);
        for(int i=0;i<12;i++){
            var run=sessions.start(sessionId,user,UUID.randomUUID().toString(),"accepted "+"a".repeat(750),"trace");
            sessions.append(sessionId,run.runId(),"assistant","answer "+"b".repeat(750),null,null);
            sessions.complete(sessionId,run.runId(),"done",1);
        }
        jdbc.update("INSERT INTO agent_summary(session_id,version,covered_through_seq,summary_json) VALUES(?,1,13,?)",sessionId,"{\"facts\":[\"rejected-write-intent\"],\"entities\":[],\"pending\":[],\"constraints\":[]}");
        assertTrue(context.estimateTokens(context.build(sessionId),List.of())>properties.contextSoftTokenBudget());
        when(llm.summarize(eq("review-key"),anyList())).thenAnswer(call->{
            assertFalse(call.getArgument(1).toString().contains("rejected-write-intent"));
            return "{\"facts\":[\"safe-summary\"],\"entities\":[],\"pending\":[],\"constraints\":[]}";
        });
        when(llm.decide(eq("review-key"),anyList(),anyList())).thenAnswer(call->{
            assertFalse(call.getArgument(1).toString().contains("rejected-write-intent"));
            return new LlmDecision("ok","stop",List.of(),null);
        });
        assertEquals("SUCCEEDED",loop.run(sessionId,user,"corrected","current-question","review-key","trace").status());
        verify(llm,times(1)).summarize(eq("review-key"),anyList());
    }

    private void fail(String text,String code,int loops,boolean toolEvidence){
        var run=sessions.start(sessionId,user,UUID.randomUUID().toString(),text,"trace");
        if(toolEvidence){sessions.append(sessionId,run.runId(),"assistant","planned-tool",null,null);sessions.append(sessionId,run.runId(),"tool","written",null,null);}
        sessions.fail(sessionId,run.runId(),code,loops);
    }
}
