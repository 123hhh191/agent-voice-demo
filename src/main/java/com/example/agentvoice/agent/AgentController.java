package com.example.agentvoice.agent;

import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.common.TraceContext;
import com.example.agentvoice.common.UserIdentity;
import com.example.agentvoice.config.DeepSeekProperties;
import com.example.agentvoice.session.SessionService;
import com.example.agentvoice.llm.DeepSeekApiKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@org.springframework.context.annotation.Profile("!scaffold")
@RequestMapping("/api")
@Validated
public class AgentController {
    private final SessionService sessions; private final AgentLoop agent; private final DeepSeekProperties properties; private final UserIdentity identity;
    public AgentController(SessionService sessions,AgentLoop agent,DeepSeekProperties properties,UserIdentity identity){this.sessions=sessions;this.agent=agent;this.properties=properties;this.identity=identity;}
    /** 为当前用户创建会话。 */
    @PostMapping("/sessions")
    public Map<String,Object> create(Principal principal,HttpServletRequest request){String user=user(principal,request);return Map.of("sessionId",sessions.create(user));}
    /** 校验请求与 API Key 后执行一轮 Agent 对话。 */
    @PostMapping("/sessions/{id}/messages")
    public SessionService.Run send(@PathVariable String id,@Valid @RequestBody MessageRequest body,@RequestHeader(value="X-DeepSeek-Api-Key",required=false) String apiKey,Principal principal,HttpServletRequest request){
            String user=user(principal,request);
        if(body.text().length()>properties.maxInputChars())throw new ApiException(HttpStatus.BAD_REQUEST,"INPUT_TOO_LARGE","输入内容超出长度限制");
        return agent.run(id,user,body.requestId(),body.text(),DeepSeekApiKey.requireValid(apiKey),TraceContext.current());
    }
    /** 查询当前用户有权访问的运行结果。 */
    @GetMapping("/runs/{runId}")
    public SessionService.Run run(@PathVariable String runId,Principal principal,HttpServletRequest request){return sessions.getRun(runId,user(principal,request));}
    /** 分页读取当前用户会话中的消息。 */
    @GetMapping("/sessions/{id}/messages")
    public Map<String,Object> messages(@PathVariable String id,@RequestParam(defaultValue="0") @Min(0) long afterSeq,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,Principal principal,HttpServletRequest request){
        String user=user(principal,request);if(!sessions.owns(id,user))throw new ApiException(HttpStatus.NOT_FOUND,"SESSION_NOT_FOUND","会话不存在");
        var rows=sessions.history(id,afterSeq,Math.min(limit,properties.maxPageSize()));long next=rows.isEmpty()?afterSeq:((Number)rows.get(rows.size()-1).get("seq")).longValue();
        return Map.of("messages",rows,"nextAfterSeq",next);
    }
    private String user(Principal principal,HttpServletRequest request){return identity.user(request);}
    public record Identity() { }
    public record MessageRequest(@NotBlank @Size(max=8000) String text,@NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9._:-]+") String requestId) { }
}
