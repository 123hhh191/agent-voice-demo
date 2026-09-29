package com.example.agentvoice.tool;

import com.example.agentvoice.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Component
@org.springframework.context.annotation.Profile("!scaffold")
public class ToolRegistry {
    private final ObjectMapper mapper;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    public ToolRegistry(ObjectMapper mapper, TodoTool todo) {
        this.mapper = mapper;
        register("calculator", "计算仅包含数字、括号和四则运算的表达式。", Map.of("expression", Map.of("type", "string", "minLength", 1, "maxLength", 256)), List.of("expression"), "READ_ONLY", "SAFE", 3000, 2, false, (a,c) -> CalculatorTool.calculate(a.path("expression").asText()));
        register("search", "演示搜索，返回明确标记的模拟数据。", Map.of("query", Map.of("type", "string", "minLength", 1, "maxLength", 200)), List.of("query"), "READ_ONLY", "SAFE", 3000, 2, false, (a,c) -> Map.of("source", "mock", "query", a.path("query").asText(), "results", List.of()));
        register("weather", "演示天气，仅返回明确标记的模拟数据。", Map.of("city", Map.of("type", "string", "minLength", 1, "maxLength", 80), "date", Map.of("type", "string", "format", "date")), List.of("city", "date"), "READ_ONLY", "SAFE", 3000, 2, false, (a,c) -> Map.of("source", "mock", "city", a.path("city").asText(), "date", a.path("date").asText(), "forecast", "演示天气数据"));
        register("todo_create", "在当前会话创建待办。", Map.of("title", Map.of("type", "string", "minLength", 1, "maxLength", 240), "dueAt", Map.of("type", List.of("string", "null"), "format", "date-time")), List.of("title", "dueAt"), "WRITE", "SAME_KEY_ONLY", 5000, 1, true, todo::create);
        register("todo_list", "查询当前会话待办。", Map.of("status", Map.of("type", "string", "enum", List.of("PENDING", "COMPLETED", "ALL"))), List.of("status"), "READ_ONLY", "SAFE", 3000, 2, false, todo::list);
        register("todo_update", "按版本更新当前会话待办。", Map.of("todoId", Map.of("type", "string"), "expectedVersion", Map.of("type", "integer", "minimum", 0), "title", Map.of("type", "string", "minLength", 1, "maxLength", 240), "dueAt", Map.of("type", List.of("string", "null"), "format", "date-time"), "status", Map.of("type", "string", "enum", List.of("PENDING", "COMPLETED"))), List.of("todoId", "expectedVersion"), "WRITE", "NEVER", 5000, 1, false, todo::update);
    }
    private void register(String name, String description, Map<String,Object> props, List<String> required, String effectType, String retryMode, long timeoutMs, int maxAttempts, boolean supportsStatusQuery, BiFunction<JsonNode,ToolExecutionContext,Object> action) {
        if (tools.containsKey(name)) throw new IllegalStateException("重复工具名: " + name);
        Map<String,Object> schema = new LinkedHashMap<>(); schema.put("type", "object"); schema.put("properties", props); schema.put("required", required); schema.put("additionalProperties", false);
        tools.put(name, new Tool(Map.of("type", "function", "function", Map.of("name", name, "description", description, "parameters", schema)), action, props, required, new Metadata(effectType,retryMode,timeoutMs,maxAttempts,supportsStatusQuery)));
    }
    public List<Map<String,Object>> definitions() { return tools.values().stream().map(Tool::definition).toList(); }
    public Metadata metadata(String name) { Tool tool=tools.get(name); if(tool==null)throw new ApiException(HttpStatus.BAD_REQUEST,"UNKNOWN_TOOL","不支持的工具"); return tool.metadata(); }
    public Object execute(String name, String json, ToolExecutionContext context) {
        Tool tool = tools.get(name);
        if (tool == null) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_TOOL", "不支持的工具");
        JsonNode args;
        try {
            args = mapper.readTree(json);
            if (!args.isObject()) throw new IllegalArgumentException();
            validateSchema(args, tool);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOOL_ARGUMENTS", "工具参数无效"); }

        // Tool execution can fail because of business rules or infrastructure; preserve that error.
        return tool.action().apply(args, context);
    }
    private void validateSchema(JsonNode args, Tool tool) {
        var fields = args.fieldNames();
        while (fields.hasNext()) if (!tool.propertySchemas().containsKey(fields.next())) throw new IllegalArgumentException();
        for (String required : tool.required()) if (!args.has(required)) throw new IllegalArgumentException();
        for (var entry : args.properties()) {
            JsonNode value = entry.getValue(); Map<?,?> schema=(Map<?,?>)tool.propertySchemas().get(entry.getKey());
            if (value.isContainerNode()) throw new IllegalArgumentException();
            Object type=schema.get("type");
            if (value.isNull()) { if (!(type instanceof List<?> types && types.contains("null"))) throw new IllegalArgumentException(); continue; }
            if (type instanceof String t && t.equals("string")) {
                if (!value.isTextual()) throw new IllegalArgumentException(); String s=value.asText();
                if (schema.get("minLength") instanceof Number n && s.length()<n.intValue()) throw new IllegalArgumentException();
                if (schema.get("maxLength") instanceof Number n && s.length()>n.intValue()) throw new IllegalArgumentException();
                if (schema.get("enum") instanceof List<?> values && !values.contains(s)) throw new IllegalArgumentException();
                if ("date".equals(schema.get("format"))) java.time.LocalDate.parse(s);
                if ("date-time".equals(schema.get("format"))) java.time.OffsetDateTime.parse(s);
            } else if (type instanceof List<?> types && types.contains("string")) {
                if (!value.isTextual()) throw new IllegalArgumentException();
                if ("date-time".equals(schema.get("format"))) java.time.OffsetDateTime.parse(value.asText());
            } else if ("integer".equals(type)) {
                if (!value.isIntegralNumber() || (schema.get("minimum") instanceof Number n && value.longValue()<n.longValue())) throw new IllegalArgumentException();
            } else throw new IllegalArgumentException();
        }
    }
    public record Metadata(String effectType,String retryMode,long timeoutMs,int maxAttempts,boolean supportsStatusQuery) { }
    private record Tool(Map<String,Object> definition, BiFunction<JsonNode,ToolExecutionContext,Object> action, Map<String,Object> propertySchemas, List<String> required, Metadata metadata) { }
}
