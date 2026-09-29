package com.example.agentvoice.llm;

import com.example.agentvoice.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekResponseParserTest {
    private final DeepSeekClient client=new DeepSeekClient(new DeepSeekProperties(URI.create("https://api.deepseek.com"),"test","test","disabled",Duration.ofSeconds(1),Duration.ofSeconds(1),8,12000,8000,100),new ObjectMapper());
    @Test void parsesEveryToolCallAndPreservesIds(){
        var result=client.parse(json("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\\\"1+2\\\"}\"}},{\"id\":\"call-2\",\"function\":{\"name\":\"todo_list\",\"arguments\":\"{\\\"status\\\":\\\"ALL\\\"}\"}}]}}]}"));
        assertEquals(List.of("call-1","call-2"),result.toolCalls().stream().map(LlmDecision.ToolCall::id).toList());
    }
    @Test void rejectsInvalidToolJson(){assertThrows(RuntimeException.class,()->client.parse(json("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"calculator\",\"arguments\":\"not-json\"}}]}}]}")));}
    @Test void rejectsEmptyAndTruncatedResponses(){assertThrows(RuntimeException.class,()->client.parse(json("{\"choices\":[]}")));assertThrows(RuntimeException.class,()->client.parse(json("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}")));}
    @Test void acceptsCompleteFinalAnswer(){assertEquals("hello",client.parse(json("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}")).content());}
    @Test void requiresNormalStopForFinalAnswer(){assertThrows(RuntimeException.class,()->client.parse(json("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"looks complete\"}}]}")));}
    private com.fasterxml.jackson.databind.JsonNode json(String s){try{return new ObjectMapper().readTree(s);}catch(Exception e){throw new AssertionError(e);}}
}
