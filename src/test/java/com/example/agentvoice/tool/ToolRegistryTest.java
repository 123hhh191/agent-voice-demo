package com.example.agentvoice.tool;

import com.example.agentvoice.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.List;

class ToolRegistryTest {
    @Test void exposesSearchAndWeatherSchemasAndClearlyMarksMockResults(){
        ToolRegistry registry=new ToolRegistry(new ObjectMapper(),mock(TodoTool.class));

        Map<String,Object> search=registry.definitions().stream()
                .filter(d->"search".equals(((Map<?,?>)d.get("function")).get("name"))).findFirst().orElseThrow();
        Map<String,Object> weather=registry.definitions().stream()
                .filter(d->"weather".equals(((Map<?,?>)d.get("function")).get("name"))).findFirst().orElseThrow();
        Map<?,?> searchFunction=(Map<?,?>)search.get("function");
        Map<?,?> weatherFunction=(Map<?,?>)weather.get("function");
        Map<?,?> searchSchema=(Map<?,?>)searchFunction.get("parameters");
        Map<?,?> weatherSchema=(Map<?,?>)weatherFunction.get("parameters");
        assertEquals(List.of("query"),searchSchema.get("required"));
        assertEquals(List.of("city","date"),weatherSchema.get("required"));
        assertEquals("date",((Map<?,?>)((Map<?,?>)weatherSchema.get("properties")).get("date")).get("format"));
        assertEquals(false,searchSchema.get("additionalProperties"));

        var context=new ToolExecutionContext("u","s","r");
        assertEquals("mock",((Map<?,?>)registry.execute("search","{\"query\":\"天气\"}",context)).get("source"));
        assertEquals("mock",((Map<?,?>)registry.execute("weather","{\"city\":\"Tokyo\",\"date\":\"2026-09-29\"}",context)).get("source"));
    }

    @Test void rejectsInvalidSearchAndWeatherArguments(){
        ToolRegistry registry=new ToolRegistry(new ObjectMapper(),mock(TodoTool.class));
        var context=new ToolExecutionContext("u","s","r");

        assertThrows(ApiException.class,()->registry.execute("search","{\"query\":\"\"}",context));
        assertThrows(ApiException.class,()->registry.execute("weather","{\"city\":\"Tokyo\",\"date\":\"tomorrow\"}",context));
        assertThrows(ApiException.class,()->registry.execute("weather","{\"city\":\"Tokyo\",\"date\":\"2026-09-29\",\"extra\":true}",context));
    }

    @Test void rejectsUnknownAndMalformedArgumentsBeforeDispatch(){
        TodoTool todo=mock(TodoTool.class);ToolRegistry registry=new ToolRegistry(new ObjectMapper(),todo);
        var context=new ToolExecutionContext("u","s","r");
        assertThrows(RuntimeException.class,()->registry.execute("missing","{}",context));
        assertThrows(RuntimeException.class,()->registry.execute("todo_create","{\"title\":\"x\",\"dueAt\":null,\"userId\":\"other\"}",context));
        assertThrows(RuntimeException.class,()->registry.execute("calculator","{\"expression\":7}",context));
        verifyNoInteractions(todo);
    }

    @Test void validatesEveryFieldAfterNullableNullRegardlessOfFieldOrder(){
        TodoTool todo=mock(TodoTool.class);ToolRegistry registry=new ToolRegistry(new ObjectMapper(),todo);
        var context=new ToolExecutionContext("u","s","r");

        ApiException error=assertThrows(ApiException.class,()->registry.execute("todo_update",
                "{\"dueAt\":null,\"todoId\":\"id\",\"expectedVersion\":\"0\",\"title\":123}",context));

        assertEquals("INVALID_TOOL_ARGUMENTS",error.code());
        verify(todo,never()).update(any(),any());
    }

    @Test void validatesRemainingFieldsWhenNullableNullAppearsAfterInvalidField(){
        TodoTool todo=mock(TodoTool.class);ToolRegistry registry=new ToolRegistry(new ObjectMapper(),todo);
        var context=new ToolExecutionContext("u","s","r");

        ApiException error=assertThrows(ApiException.class,()->registry.execute("todo_update",
                "{\"todoId\":\"id\",\"expectedVersion\":\"0\",\"dueAt\":null}",context));

        assertEquals("INVALID_TOOL_ARGUMENTS",error.code());
        verify(todo,never()).update(any(),any());
    }

    @Test void allowsNullableNullAndDispatchesAfterCompleteValidation(){
        TodoTool todo=mock(TodoTool.class);ToolRegistry registry=new ToolRegistry(new ObjectMapper(),todo);
        var context=new ToolExecutionContext("u","s","r");

        registry.execute("todo_update","{\"todoId\":\"id\",\"expectedVersion\":0,\"dueAt\":null}",context);

        verify(todo).update(any(),eq(context));
    }

    @Test void rejectsNullForNonNullableFieldsUnknownFieldsAndNonIntegerVersions(){
        TodoTool todo=mock(TodoTool.class);ToolRegistry registry=new ToolRegistry(new ObjectMapper(),todo);
        var context=new ToolExecutionContext("u","s","r");

        assertInvalid(registry,context,"todo_update","{\"todoId\":null,\"expectedVersion\":0}");
        assertInvalid(registry,context,"todo_update","{\"todoId\":\"id\",\"expectedVersion\":0,\"extra\":true}");
        assertInvalid(registry,context,"todo_update","{\"todoId\":\"id\",\"expectedVersion\":1.5}");
        verifyNoInteractions(todo);
    }

    @Test void preservesToolExecutionFailuresInsteadOfClassifyingThemAsInvalidArguments(){
        TodoTool todo=mock(TodoTool.class);ToolRegistry registry=new ToolRegistry(new ObjectMapper(),todo);
        var context=new ToolExecutionContext("u","s","r");
        var databaseFailure=new DataAccessResourceFailureException("database unavailable");
        when(todo.update(any(),any())).thenThrow(databaseFailure);

        DataAccessResourceFailureException thrown=assertThrows(DataAccessResourceFailureException.class,
                ()->registry.execute("todo_update","{\"todoId\":\"id\",\"expectedVersion\":0}",context));

        assertSame(databaseFailure,thrown);
    }

    @Test void preservesBusinessApiExceptionFromToolExecution(){
        TodoTool todo=mock(TodoTool.class);ToolRegistry registry=new ToolRegistry(new ObjectMapper(),todo);
        var context=new ToolExecutionContext("u","s","r");
        var businessFailure=new ApiException(org.springframework.http.HttpStatus.CONFLICT,"TODO_VERSION_CONFLICT","版本冲突");
        when(todo.update(any(),any())).thenThrow(businessFailure);

        ApiException thrown=assertThrows(ApiException.class,
                ()->registry.execute("todo_update","{\"todoId\":\"id\",\"expectedVersion\":0}",context));

        assertSame(businessFailure,thrown);
        assertEquals("TODO_VERSION_CONFLICT",thrown.code());
    }

    private void assertInvalid(ToolRegistry registry,ToolExecutionContext context,String tool,String json){
        ApiException error=assertThrows(ApiException.class,()->registry.execute(tool,json,context));
        assertEquals("INVALID_TOOL_ARGUMENTS",error.code());
    }
}
