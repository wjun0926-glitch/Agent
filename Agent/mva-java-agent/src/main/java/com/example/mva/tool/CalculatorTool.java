package com.example.mva.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Safe arithmetic expression evaluator.
 * <p>
 * Supports +, -, *, /, parentheses, decimal numbers.
 * Uses a hand-written recursive-descent parser — no ScriptEngine.
 */
@Component
public class CalculatorTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Evaluate a mathematical expression. Supports +, -, *, /, parentheses, and decimal numbers. " +
               "Examples: '123 * 456', '(1.5 + 2) * 3'";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "expression", Map.of(
                                "type", "string",
                                "description", "The mathematical expression to evaluate"
                        )
                ),
                "required", List.of("expression")
        );
    }

    @Override
    public String execute(String jsonArgs, String sessionId) {
        try {
            JsonNode node = MAPPER.readTree(jsonArgs);
            String expr = node.get("expression").asText();
            // Normalise Chinese/Unicode operators
            expr = normalise(expr);
            double result = new Parser(expr).parse();
            // Return integer if result is a whole number
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "计算错误: " + e.getMessage();
        }
    }

    private static String normalise(String s) {
        return s
                .replace("×", "*").replace("X", "*").replace("ｘ", "*")
                .replace("÷", "/")
                .replace("＋", "+").replace("加", "+")
                .replace("－", "-").replace("减", "-")
                .replace("乘以", "*").replace("乘", "*")
                .replace("除以", "/").replace("除", "/")
                .replace("（", "(").replace("）", ")")
                .replace("＝", "=")
                .replaceAll("\\s+", "");
    }

    /* ================================================================
     *  Recursive-descent expression parser
     * ================================================================ */

    private enum TokenType { NUMBER, PLUS, MINUS, STAR, SLASH, LPAREN, RPAREN, EOF }

    private record Token(TokenType type, double value) { }

    private static class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(String input) {
            this.tokens = tokenise(input);
            this.pos = 0;
        }

        private static List<Token> tokenise(String s) {
            var list = new ArrayList<Token>();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == '.' || Character.isDigit(c)) {
                    int start = i;
                    while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                    list.add(new Token(TokenType.NUMBER, Double.parseDouble(s.substring(start, i))));
                    continue;
                }
                list.add(switch (c) {
                    case '+' -> new Token(TokenType.PLUS, 0);
                    case '-' -> new Token(TokenType.MINUS, 0);
                    case '*' -> new Token(TokenType.STAR, 0);
                    case '/' -> new Token(TokenType.SLASH, 0);
                    case '(' -> new Token(TokenType.LPAREN, 0);
                    case ')' -> new Token(TokenType.RPAREN, 0);
                    default -> throw new IllegalArgumentException("非法字符: '" + c + "'");
                });
                i++;
            }
            list.add(new Token(TokenType.EOF, 0));
            return list;
        }

        private Token peek() { return tokens.get(pos); }
        private Token consume() { return tokens.get(pos++); }

        private Token expect(TokenType tt) {
            var t = consume();
            if (t.type() != tt)
                throw new IllegalArgumentException("期望 " + tt + "，实际得到 " + t.type());
            return t;
        }

        double parse() {
            double r = expression();
            if (peek().type() != TokenType.EOF)
                throw new IllegalArgumentException("表达式末尾有多余字符");
            return r;
        }

        private double expression() {
            double r = term();
            while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS) {
                if (consume().type() == TokenType.PLUS) r += term();
                else r -= term();
            }
            return r;
        }

        private double term() {
            double r = factor();
            while (peek().type() == TokenType.STAR || peek().type() == TokenType.SLASH) {
                var op = consume();
                double right = factor();
                r = (op.type() == TokenType.STAR) ? r * right : r / right;
            }
            return r;
        }

        private double factor() {
            if (peek().type() == TokenType.LPAREN) {
                consume(); // '('
                double r = expression();
                expect(TokenType.RPAREN);
                return r;
            }
            return expect(TokenType.NUMBER).value();
        }
    }
}
