package com.agridispatch.linebot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智農派工 - 最小可用 JSON 解析器 (零第三方依賴)
 * 僅供內部解析 LINE Webhook 請求內容、/api/config 表單等簡單 JSON 結構使用。
 * 正確處理巢狀 object/array、字串跳脫字元 (含四位十六進位 unicode 跳脫序列)，
 * 避免專案先前以「全域 Regex」擷取欄位時，遇到巢狀結構或跳脫字元即誤判的問題。
 */
public final class SimpleJson {
    private final String src;
    private int pos;

    private SimpleJson(String src) {
        this.src = src == null ? "" : src;
        this.pos = 0;
    }

    /**
     * 解析任意 JSON 值 (object / array / string / number / boolean / null)
     */
    public static Object parse(String json) {
        SimpleJson parser = new SimpleJson(json);
        parser.skipWhitespace();
        if (parser.pos >= parser.src.length()) {
            return null;
        }
        try {
            return parser.parseValue();
        } catch (Exception e) {
            // 輸入非合法 JSON 時，不拋出例外中斷主流程，回傳 null 讓呼叫端自行判斷/降級處理
            return null;
        }
    }

    /**
     * 解析 JSON 物件，若輸入非物件或解析失敗則回傳空 Map (呼叫端不需另外判斷型別)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalStateException("未預期的結尾");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObjectInternal();
            case '[':
                return parseArrayInternal();
            case '"':
                return parseString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObjectInternal() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // 跳過 '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            skipWhitespace();
            if (peek() != '"') {
                break; // 結構不合法，直接結束並回傳已解析內容
            }
            String key = parseString();
            skipWhitespace();
            if (peek() == ':') {
                pos++;
            }
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            } else if (next == '}') {
                pos++;
                break;
            } else {
                break;
            }
        }
        return map;
    }

    private List<Object> parseArrayInternal() {
        List<Object> list = new ArrayList<>();
        pos++; // 跳過 '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (pos < src.length()) {
            Object value = parseValue();
            list.add(value);
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            } else if (next == ']') {
                pos++;
                break;
            } else {
                break;
            }
        }
        return list;
    }

    private String parseString() {
        skipWhitespace();
        if (peek() != '"') {
            return "";
        }
        pos++; // 跳過開頭雙引號
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\' && pos < src.length()) {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 <= src.length()) {
                            String hex = src.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ignored) {
                                // 忽略非法 \\u 跳脫序列
                            }
                            pos += 4;
                        }
                        break;
                    default:
                        sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object parseNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String numStr = src.substring(start, pos);
        if (numStr.isEmpty()) {
            // 非預期字元，往前推進一格避免無窮迴圈
            pos++;
            return null;
        }
        try {
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void expectLiteral(String literal) {
        if (src.regionMatches(pos, literal, 0, literal.length())) {
            pos += literal.length();
        } else {
            pos++;
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
