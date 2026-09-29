package com.example.agentvoice.tool;

import java.math.BigDecimal;
import java.math.MathContext;

public final class CalculatorTool {
    private CalculatorTool() { }
    /** 解析并计算仅含括号与四则运算的安全表达式。 */
    public static BigDecimal calculate(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 256) throw new IllegalArgumentException("表达式长度无效");
        Parser parser = new Parser(expression);
        BigDecimal value = parser.expression(); parser.space();
        if (!parser.end()) throw new IllegalArgumentException("仅允许数字、括号和四则运算");
        if (value.precision() > 32 || Math.abs(value.scale()) > 32) throw new IllegalArgumentException("结果精度超限");
        return value.stripTrailingZeros();
    }
    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s=s; }
        boolean end(){return i==s.length();} void space(){while(!end()&&Character.isWhitespace(s.charAt(i)))i++;}
        // expression/term/factor 分层实现运算优先级：加减、乘除、括号与数字。
        BigDecimal expression(){BigDecimal n=term(); while(true){space(); if(take('+'))n=n.add(term(),MathContext.DECIMAL128);else if(take('-'))n=n.subtract(term(),MathContext.DECIMAL128);else return n;}}
        BigDecimal term(){BigDecimal n=factor();while(true){space();if(take('*'))n=n.multiply(factor(),MathContext.DECIMAL128);else if(take('/')){BigDecimal d=factor();if(d.signum()==0)throw new ArithmeticException("除数不能为零");n=n.divide(d,MathContext.DECIMAL128);}else return n;}}
        BigDecimal factor(){space();if(take('+'))return factor();if(take('-'))return factor().negate();if(take('(')){BigDecimal n=expression();space();if(!take(')'))throw new IllegalArgumentException("括号不匹配");return n;}int start=i;while(!end()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'))i++;if(start==i)throw new IllegalArgumentException("数字无效");return new BigDecimal(s.substring(start,i));}
        boolean take(char c){if(!end()&&s.charAt(i)==c){i++;return true;}return false;}
    }
}
