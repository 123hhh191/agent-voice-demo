package com.example.agentvoice.agent;

import com.example.agentvoice.config.DeepSeekProperties;
import com.example.agentvoice.event.RunEventService;
import com.example.agentvoice.execution.ToolInvocationService;
import com.example.agentvoice.llm.DeepSeekClient;
import com.example.agentvoice.llm.LlmDecision;
import com.example.agentvoice.memory.ContextBuilder;
import com.example.agentvoice.memory.ContextCompactor;
import com.example.agentvoice.session.SessionService;
import com.example.agentvoice.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopTest {
    private final DeepSeekClient llm=mock(DeepSeekClient.class);
    private final ToolRegistry tools=mock(ToolRegistry.class);
    private final SessionService sessions=mock(SessionService.class);
    private final ContextBuilder context=mock(ContextBuilder.class);
    private final ContextCompactor compactor=mock(ContextCompactor.class);
    private final ToolInvocationService invocations=mock(ToolInvocationService.class);
    private final RunEventService events=mock(RunEventService.class);
    private AgentLoop loop(int limit){when(invocations.prepare(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new ToolInvocationService.Invocation("inv","hash","{}","PREPARED",null));when(invocations.claim(anyString(),anyLong())).thenReturn(true);when(invocations.complete(anyString(),anyLong(),anyString(),any())).thenReturn(true);return new AgentLoop(llm,tools,sessions,context,compactor,properties(limit),new ObjectMapper(),invocations,events);}

    @Test void directCallerCannotPersistRunWithMalformedKey(){
        assertThrows(com.example.agentvoice.common.ApiException.class,()->loop(8).run("s","u","q","hello","x".repeat(513),"trace"));
        verifyNoInteractions(sessions,llm,context,events);
    }

    @Test void overBudgetContextIsRebuiltBeforeModelDecision(){
        when(sessions.start(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(running());
        when(tools.definitions()).thenReturn(List.of());
        when(context.build("s")).thenReturn(List.of(Map.of("role","user","content","history")));
        when(context.estimateTokens(anyList(),anyList())).thenReturn(13000,100,100);
        when(llm.decide(anyString(),anyList(),anyList())).thenReturn(new LlmDecision("ok","stop",List.of(),null));
        assertEquals("SUCCEEDED",loop(8).run("s","u","q","hello","test-key","trace").status());
        var order=inOrder(context,compactor,llm);
        order.verify(context).build("s");
        order.verify(compactor).compact("s","test-key");
        order.verify(context).build("s");
        order.verify(llm).decide(eq("test-key"),anyList(),anyList());
    }

    @Test void directAnswerUsesNoToolAndCompletesRun(){
        when(sessions.start(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(running());
        when(tools.definitions()).thenReturn(List.of());when(context.build("s")).thenReturn(List.of(Map.of("role","user","content","hello")));
        when(context.estimateTokens(anyList(),anyList())).thenReturn(1);when(llm.decide(anyString(),anyList(),anyList())).thenReturn(new LlmDecision("你好","stop",List.of(),null));
        var result=loop(8).run("s","u","q","hello","test-key","trace");
        assertEquals("你好",result.answer());verify(tools,never()).execute(anyString(),anyString(),any());verify(sessions).complete("s","r","你好",1);
    }

    @Test void toolResultIsStoredWithOriginalCallIdBeforeFinalAnswer(){
        when(sessions.start(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(running());
        when(tools.definitions()).thenReturn(List.of());when(context.build("s")).thenReturn(List.of(Map.of("role","user","content","calculate")));
        when(context.estimateTokens(anyList(),anyList())).thenReturn(1);
        when(llm.decide(anyString(),anyList(),anyList())).thenReturn(new LlmDecision(null,"tool_calls",List.of(new LlmDecision.ToolCall("call-1","calculator","{\"expression\":\"1+2\"}")),null),new LlmDecision("结果是 3","stop",List.of(),null));
        when(tools.execute(eq("calculator"),anyString(),any())).thenReturn(3);
        var result=loop(8).run("s","u","q","calculate","test-key","trace");
        assertEquals("结果是 3",result.answer());verify(sessions).append(eq("s"),eq("r"),eq("tool"),contains("3"),eq("call-1"),isNull());
        verify(events).enqueue(eq("r"),eq("tool.completed"),argThat(payload->payload.toString().contains("call-1")));
        verify(events).enqueue(eq("r"),eq("run.completed"),any());
    }

    @Test void decisionLimitStopsFurtherModelCalls(){
        when(sessions.start(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(running());
        when(tools.definitions()).thenReturn(List.of());when(context.build("s")).thenReturn(List.of(Map.of("role","user","content","calculate")));
        when(context.estimateTokens(anyList(),anyList())).thenReturn(1);
        when(llm.decide(anyString(),anyList(),anyList())).thenReturn(new LlmDecision(null,"tool_calls",List.of(new LlmDecision.ToolCall("call-1","calculator","{\"expression\":\"1+2\"}")),null));
        when(tools.execute(eq("calculator"),anyString(),any())).thenReturn(3);
        assertThrows(RuntimeException.class,()->loop(1).run("s","u","q","calculate","test-key","trace"));
        verify(llm,times(1)).decide(anyString(),anyList(),anyList());verify(sessions).fail("s","r","AGENT_DECISION_LIMIT",1);
    }

    private SessionService.Run running(){return new SessionService.Run("r","RUNNING",null,null,"trace");}
    private DeepSeekProperties properties(int limit){return new DeepSeekProperties(URI.create("https://api.deepseek.com"),"test","test","disabled",Duration.ofSeconds(1),Duration.ofSeconds(1),limit,12000,8000,100);}
}
