package com.example.agentvoice.agent;

import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.config.DeepSeekProperties;
import com.example.agentvoice.execution.ToolInvocationService;
import com.example.agentvoice.event.RunEventService;
import com.example.agentvoice.llm.DeepSeekClient;
import com.example.agentvoice.llm.DeepSeekApiKey;
import com.example.agentvoice.llm.LlmDecision;
import com.example.agentvoice.memory.ContextBuilder;
import com.example.agentvoice.memory.ContextCompactor;
import com.example.agentvoice.session.SessionService;
import com.example.agentvoice.tool.ToolExecutionContext;
import com.example.agentvoice.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class AgentLoop {
    private final DeepSeekClient llm; private final ToolRegistry tools; private final SessionService sessions;
    private final ContextBuilder context; private final ContextCompactor compactor; private final DeepSeekProperties properties; private final ObjectMapper mapper; private final ToolInvocationService invocations; private final RunEventService events;
    public AgentLoop(DeepSeekClient llm,ToolRegistry tools,SessionService sessions,ContextBuilder context,ContextCompactor compactor,DeepSeekProperties properties,ObjectMapper mapper,ToolInvocationService invocations,RunEventService events){this.llm=llm;this.tools=tools;this.sessions=sessions;this.context=context;this.compactor=compactor;this.properties=properties;this.mapper=mapper;this.invocations=invocations;this.events=events;}
    /** 执行一轮对话，按需调用工具并保存运行结果。 */
    public SessionService.Run run(String sessionId,String userId,String requestId,String text,String apiKey,String traceId){
        apiKey=DeepSeekApiKey.requireValid(apiKey);
        SessionService.Run run=sessions.start(sessionId,userId,requestId,text,traceId);
        if(!"RUNNING".equals(run.status()))return run;
        recordEvent(run.runId(),"run.started",Map.of("runId",run.runId(),"status","RUNNING"));
        int decisions=0;
        Instant deadline=Instant.now().plusSeconds(60);
        try{
            ToolExecutionContext execution=new ToolExecutionContext(userId,sessionId,run.runId());
            for(;decisions<properties.maxDecisions();decisions++){
                if(Instant.now().isAfter(deadline))throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"RUN_DEADLINE","运行超时");
                List<Map<String,Object>> definitions=tools.definitions();
                List<Map<String,Object>> messages=context.build(sessionId);
                if(context.estimateTokens(messages,definitions)>properties.contextSoftTokenBudget()){
                    // An unsafe legacy summary may have been bypassed; rebuild from eligible facts first.
                    compactor.compact(sessionId,apiKey);
                    messages=context.build(sessionId);
                }
                if(context.estimateTokens(messages,definitions)>properties.contextSoftTokenBudget())throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"CONTEXT_TOO_LARGE","会话上下文超出预算");
                LlmDecision decision=llm.decide(apiKey,messages,definitions);
                if(!decision.hasToolCalls()){
                    sessions.append(sessionId,run.runId(),"assistant",decision.content(),null,null);
                    sessions.complete(sessionId,run.runId(),decision.content(),decisions+1);
                    recordEvent(run.runId(),"run.completed",Map.of("runId",run.runId(),"status","SUCCEEDED"));
                    try{compactor.compact(sessionId,apiKey);}catch(RuntimeException ignored){/* 摘要失败保留完整原始消息。 */}
                    return new SessionService.Run(run.runId(),"SUCCEEDED",decision.content(),null,traceId);
                }
                List<Map<String,Object>> stored=decision.toolCalls().stream().map(c->Map.<String,Object>of("id",c.id(),"type","function","function",Map.of("name",c.name(),"arguments",c.argumentsJson()))).toList();
                sessions.append(sessionId,run.runId(),"assistant",decision.content(),null,stored);
                // 每个工具调用单独记账，避免重试时重复产生外部副作用。
                for(LlmDecision.ToolCall call:decision.toolCalls()){
                    Object result;
                    String operationId=run.runId()+":"+call.id();
                    try{
                        var invocation=invocations.prepare(run.runId(),call.id(),operationId,call.name(),call.argumentsJson());
                        if("SUCCEEDED".equals(invocation.state())) result=mapper.readTree(invocation.resultJson());
                        else if("FAILED".equals(invocation.state())||"UNKNOWN".equals(invocation.state())) result=Map.of("ok",false,"error","NEEDS_REVIEW","message","该操作已有终态记录，请查询或人工核查");
                        else if(!invocations.claim(invocation.id(),0)) result=Map.of("ok",false,"error","UNKNOWN","message","操作状态不确定，需要核查后再继续");
                        else {
                            try {
                                result=tools.execute(call.name(),invocation.argsJson(),new ToolExecutionContext(userId,sessionId,operationId));
                                if(!invocations.complete(invocation.id(),1,"SUCCEEDED",result)) result=Map.of("ok",false,"error","UNKNOWN","message","操作结果未能确认保存");
                            } catch(Exception toolError) {
                                invocations.complete(invocation.id(),1,"FAILED",Map.of("ok",false,"error",toolError instanceof ApiException ae?ae.code():"TOOL_EXECUTION_FAILED"));
                                throw toolError;
                            }
                        }
                    }
                    catch(Exception ex){String code=ex instanceof ApiException ae?ae.code():"TOOL_EXECUTION_FAILED";result=Map.of("ok",false,"error",code,"message",ex.getMessage()==null?"工具执行失败":ex.getMessage());}
                    String content=mapper.writeValueAsString(result);
                    sessions.append(sessionId,run.runId(),"tool",content,call.id(),null);
                    recordEvent(run.runId(),"tool.completed",Map.of("runId",run.runId(),"toolCallId",call.id()));
                }
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,"AGENT_DECISION_LIMIT","Agent 达到决策轮次上限");
        }catch(Exception ex){
            String code=ex instanceof ApiException ae?ae.code():"AGENT_FAILED";
            sessions.fail(sessionId,run.runId(),code,Math.min(decisions+1,properties.maxDecisions()));
            recordEvent(run.runId(),"run.failed",Map.of("runId",run.runId(),"status","FAILED","errorCode",code));
            if(ex instanceof RuntimeException re)throw re;
            throw new ApiException(HttpStatus.BAD_GATEWAY,"AGENT_FAILED","Agent 执行失败");
        }
    }
    /** 写入可补发事件；事件写入失败不改变运行主状态。 */
    private void recordEvent(String runId,String type,Map<String,Object> payload){try{events.enqueue(runId,type,payload);}catch(RuntimeException ignored){/* 运行状态以 agent_run 为准，事件可由状态查询补偿。 */}}
}
