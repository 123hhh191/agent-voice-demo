package com.example.agentvoice.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String trace = TraceContext.begin(request.getHeader("X-Request-Trace"));
        request.setAttribute("traceId", trace);
        response.setHeader("X-Trace-Id", trace);
        try { chain.doFilter(request, response); }
        finally { TraceContext.clear(); }
    }
}
