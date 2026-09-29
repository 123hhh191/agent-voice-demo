package com.example.agentvoice.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
public class UserIdentity {
    private final boolean demoAuth;
    public UserIdentity(@Value("${app.demo-auth.enabled:false}") boolean demoAuth) { this.demoAuth=demoAuth; }
    /** 从登录主体或本地演示请求头解析用户标识。 */
    public String user(HttpServletRequest request) {
        Principal principal=request.getUserPrincipal();
        if(principal!=null&&!principal.getName().isBlank()&&principal.getName().length()<=128)return principal.getName();
        if(demoAuth){String id=request.getHeader("X-Demo-User-Id");if(id!=null&&id.matches("[A-Za-z0-9_-]{1,64}"))return "demo:"+id;}
        throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","请先登录");
    }
}
