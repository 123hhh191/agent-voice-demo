package com.example.agentvoice.agent;

import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.common.UserIdentity;
import com.example.agentvoice.config.DeepSeekProperties;
import com.example.agentvoice.llm.DeepSeekApiKey;
import com.example.agentvoice.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentKeyValidationTest {
    @Test void invalidHeadersNeverReachRunCreation() {
        var sessions=mock(SessionService.class);var loop=mock(AgentLoop.class);
        var controller=new AgentController(sessions,loop,properties(),new UserIdentity(true));
        var request=new MockHttpServletRequest();request.addHeader("X-Demo-User-Id","review");
        for(String key:Arrays.asList(null,"","  ","x".repeat(513),"bad\r\nkey","bad key","bad\u0080key")){
            var ex=assertThrows(ApiException.class,()->controller.send("s",new AgentController.MessageRequest("hello","q"),key,null,request));
            assertEquals("DEEPSEEK_API_KEY_REQUIRED",ex.code());
        }
        verifyNoInteractions(sessions,loop);
    }

    @Test void validKeyIsNormalizedWithoutBeingAddedToErrors() {
        assertEquals("review-key",DeepSeekApiKey.requireValid("  review-key  "));
        assertEquals(512,DeepSeekApiKey.requireValid("x".repeat(512)).length());
        String secret="review-sensitive-value".repeat(30);
        var ex=assertThrows(ApiException.class,()->DeepSeekApiKey.requireValid(secret));
        assertFalse(ex.getMessage().contains("review-sensitive-value"));
    }

    static DeepSeekProperties properties(){return new DeepSeekProperties(URI.create("https://api.deepseek.com"),"mock",null,"disabled",Duration.ofSeconds(1),Duration.ofSeconds(1),8,12000,8000,100);}
}
