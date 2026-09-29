package com.example.agentvoice.voice;

import com.example.agentvoice.device.DeviceAuthService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@org.springframework.context.annotation.Profile("!scaffold")
@EnableWebSocket
public class DeviceWebSocketConfiguration implements WebSocketConfigurer {
    private final DeviceGateway gateway;private final DeviceAuthService auth;
    public DeviceWebSocketConfiguration(DeviceGateway gateway,DeviceAuthService auth){this.gateway=gateway;this.auth=auth;}
    /** 注册语音 WebSocket，并在握手阶段验证设备 Bearer Token。 */
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(gateway,"/device/voice").addInterceptors(new HandshakeInterceptor(){
        @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
            try{String header=request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);if(header==null||!header.startsWith("Bearer "))throw new IllegalArgumentException();String token=header.substring(7);attributes.put("deviceId",auth.authenticate(token));attributes.put("deviceToken",token);return true;}
            catch(RuntimeException ex){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
        }
        @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){}
    });}
}
