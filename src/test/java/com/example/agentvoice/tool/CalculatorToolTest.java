package com.example.agentvoice.tool;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {
    @Test void calculatesBoundedArithmetic(){assertEquals(new BigDecimal("84"),CalculatorTool.calculate("12*(3+4)"));}
    @Test void rejectsCodeAndDivisionByZero(){assertThrows(IllegalArgumentException.class,()->CalculatorTool.calculate("1+Runtime.getRuntime()"));assertThrows(ArithmeticException.class,()->CalculatorTool.calculate("1/0"));}
}
