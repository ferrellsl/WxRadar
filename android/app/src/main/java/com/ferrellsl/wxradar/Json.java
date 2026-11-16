package com.ferrellsl.wxradar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WxRadar: a small JSON reader, enough for the NWS's GeoJSON (a port of the Windows app's Json.cpp). */
public final class Json {
    public enum Type { NULL, BOOL, NUMBER, STRING, ARRAY, OBJECT }

    static final Json NONE = new Json();

    private Type kind = Type.NULL;
    private double number;
    private String text = "";
    private List<Json> items = Collections.emptyList();
    private Map<String, Json> members = Collections.emptyMap();

    /** NONE (a null) if it can't be read. */
    public static Json parse(String s) {
        if (s == null) return NONE;
        Reader r = new Reader(s);
        Json out = new Json();
        try {
            if (!r.value(out, 0) || !r.atEnd()) return NONE;
        } catch (RuntimeException e) {
            return NONE;
        }
        return out;
    }

    public Type type() { return kind; }
    public boolean isNull() { return kind == Type.NULL; }
    public boolean isArray() { return kind == Type.ARRAY; }
    public boolean isObject() { return kind == Type.OBJECT; }
    public boolean isNumber() { return kind == Type.NUMBER; }
    public boolean isString() { return kind == Type.STRING; }

    public double num() { return kind == Type.NUMBER ? number : 0; }
    public boolean bool() { return kind == Type.BOOL && number != 0; }
    public String str() { return kind == Type.STRING ? text : ""; }

    /** A member, or NONE (never fails: chains like j.get("a").get("b").at(0) are safe). */
    public Json get(String key) {
        if (kind != Type.OBJECT) return NONE;
        Json j = members.get(key);
        return j == null ? NONE : j;
    }

    public Json at(int index) {
        return kind == Type.ARRAY && index >= 0 && index < items.size() ? items.get(index) : NONE;
    }

    public int size() { return kind == Type.ARRAY ? items.size() : kind == Type.OBJECT ? members.size() : 0; }
    public List<Json> array() { return items; }
    public Map<String, Json> object() { return members; }

    private static final class Reader {
        final String s;
        int p;
        final int end;

        Reader(String s) { this.s = s; this.end = s.length(); }

        void space() {
            while (p < end) {
                char c = s.charAt(p);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
                ++p;
            }
        }

        boolean atEnd() { space(); return p >= end; }

        boolean value(Json out, int depth) {
            if (depth > 200) return false;
            space();
            if (p >= end) return false;
            switch (s.charAt(p)) {
                case '{': return object(out, depth);
                case '[': return array(out, depth);
                case '"': {
                    out.kind = Type.STRING;
                    StringBuilder b = new StringBuilder();
                    if (!string(b)) return false;
                    out.text = b.toString();
                    return true;
                }
                case 't': return word("true", out, Type.BOOL, 1);
                case 'f': return word("false", out, Type.BOOL, 0);
                case 'n': return word("null", out, Type.NULL, 0);
                default: return number(out);
            }
        }

        boolean word(String w, Json out, Type t, double v) {
            if (!s.startsWith(w, p)) return false;
            p += w.length();
            out.kind = t;
            out.number = v;
            return true;
        }

        boolean number(Json out) {
            int start = p;
            while (p < end) {
                char c = s.charAt(p);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') ++p;
                else break;
            }
            if (p == start) return false;
            try {
                out.number = Double.parseDouble(s.substring(start, p));
            } catch (NumberFormatException e) {
                return false;
            }
            out.kind = Type.NUMBER;
            return true;
        }

        boolean string(StringBuilder b) {
            ++p;                                          // (the opening quote)
            while (p < end && s.charAt(p) != '"') {
                char c = s.charAt(p++);
                if (c != '\\') { b.append(c); continue; }
                if (p >= end) return false;
                char e = s.charAt(p++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (end - p < 4) return false;
                        b.append((char) Integer.parseInt(s.substring(p, p + 4), 16));   // (surrogates pair up by themselves in UTF-16)
                        p += 4;
                        break;
                    default: b.append(e); break;      // \" \\ \/
                }
            }
            if (p >= end) return false;
            ++p;
            return true;
        }

        boolean array(Json out, int depth) {
            out.kind = Type.ARRAY;
            out.items = new ArrayList<>();
            ++p;
            space();
            if (p < end && s.charAt(p) == ']') { ++p; return true; }
            while (true) {
                Json item = new Json();
                if (!value(item, depth + 1)) return false;
                out.items.add(item);
                space();
                if (p < end && s.charAt(p) == ',') { ++p; continue; }
                if (p < end && s.charAt(p) == ']') { ++p; return true; }
                return false;
            }
        }

        boolean object(Json out, int depth) {
            out.kind = Type.OBJECT;
            out.members = new LinkedHashMap<>();
            ++p;
            space();
            if (p < end && s.charAt(p) == '}') { ++p; return true; }
            while (true) {
                space();
                if (p >= end || s.charAt(p) != '"') return false;
                StringBuilder key = new StringBuilder();
                if (!string(key)) return false;
                space();
                if (p >= end || s.charAt(p) != ':') return false;
                ++p;
                Json v = new Json();
                if (!value(v, depth + 1)) return false;
                out.members.putIfAbsent(key.toString(), v);   // (the first, as the Windows reader finds it)
                space();
                if (p < end && s.charAt(p) == ',') { ++p; continue; }
                if (p < end && s.charAt(p) == '}') { ++p; return true; }
                return false;
            }
        }
    }
}
