package com.github.claudecodegui.path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Applies a {@link PathMapper} to a JSON tree, driven by the path-expression
 * manifest in {@link PathFields}.
 *
 * <p>The visitor implements a tiny JSONPath subset:
 * <ul>
 *   <li>{@code $.a.b.c}  — object descent</li>
 *   <li>{@code $.a[*].b} — array element iteration, then descent</li>
 *   <li>{@code $.a.*}    — translate every <em>key</em> of an object (e.g.
 *       {@code openedFiles} maps path → metadata)</li>
 * </ul>
 *
 * <p>Translation is fail-soft: missing fields, null values, and unexpected
 * types are skipped silently — they cannot block a request.
 */
public final class PathFieldVisitor {

    private static final String STAR_KEY    = "*";
    private static final String STAR_INDEX  = "[*]";

    private PathFieldVisitor() {}

    public static void applyOutbound(String methodKey, JsonObject root, UnaryOperator<String> toRemote) {
        apply(methodKey, root, toRemote, PathFields.OUTBOUND);
    }

    public static void applyInbound(String eventKey, JsonObject root, UnaryOperator<String> toLocal) {
        apply(eventKey, root, toLocal, PathFields.INBOUND);
    }

    /**
     * Read-only walk: invoke {@code visitor} for each path-string leaf found
     * at the expressions registered under {@code eventKey} in
     * {@link PathFields#INBOUND}. The JSON tree is not mutated.
     */
    public static void collectInbound(String eventKey, JsonObject root, Consumer<String> visitor) {
        if (eventKey == null || root == null || visitor == null) return;
        List<String> exprs = PathFields.INBOUND.get(eventKey);
        if (exprs == null || exprs.isEmpty()) return;
        for (String expr : exprs) {
            List<String> tokens = splitTokens(expr);
            if (!tokens.isEmpty()) {
                walkCollect(root, tokens, 0, visitor);
            }
        }
    }

    private static void apply(String key, JsonObject root, UnaryOperator<String> fn,
                              Map<String, List<String>> manifest) {
        if (key == null || root == null) return;
        List<String> exprs = manifest.get(key);
        if (exprs == null || exprs.isEmpty()) return;
        for (String expr : exprs) {
            List<String> tokens = splitTokens(expr);
            if (!tokens.isEmpty()) {
                walk(root, tokens, 0, fn);
            }
        }
    }

    /**
     * Split a JSONPath-like expression into tokens.
     * Examples:
     *   "$.a.b.c"      → ["a", "b", "c"]
     *   "$.a[*].b"     → ["a", "[*]", "b"]
     *   "$.openedFiles.*" → ["openedFiles", "*"]
     */
    static List<String> splitTokens(String expr) {
        List<String> out = new ArrayList<>();
        if (expr == null || expr.isEmpty()) return out;
        String s = expr;
        if (s.startsWith("$")) s = s.substring(1);
        if (s.startsWith(".")) s = s.substring(1);

        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.') {
                if (buf.length() > 0) { out.add(buf.toString()); buf.setLength(0); }
                i++;
            } else if (c == '[') {
                if (buf.length() > 0) { out.add(buf.toString()); buf.setLength(0); }
                int close = s.indexOf(']', i);
                if (close < 0) {
                    // malformed — bail out
                    return new ArrayList<>();
                }
                out.add(s.substring(i, close + 1));   // keep "[*]" verbatim
                i = close + 1;
            } else {
                buf.append(c);
                i++;
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    /** Recursive descent. {@code idx} is the current token. */
    private static void walk(JsonElement node, List<String> tokens, int idx, UnaryOperator<String> fn) {
        if (node == null || node.isJsonNull()) return;

        // Reached end of the expression — translate the leaf if it is a string.
        if (idx >= tokens.size()) {
            // Should not happen in practice — leaf-handling is done one level up.
            return;
        }

        String token = tokens.get(idx);
        boolean isLast = (idx == tokens.size() - 1);

        if (STAR_INDEX.equals(token)) {
            if (!node.isJsonArray()) return;
            JsonArray arr = node.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement elem = arr.get(i);
                if (isLast) {
                    if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                        String mapped = applySafe(fn, elem.getAsString());
                        if (!Objects.equals(mapped, elem.getAsString())) {
                            arr.set(i, new JsonPrimitive(mapped));
                        }
                    }
                } else {
                    walk(elem, tokens, idx + 1, fn);
                }
            }
            return;
        }

        if (STAR_KEY.equals(token)) {
            if (!node.isJsonObject()) return;
            JsonObject obj = node.getAsJsonObject();
            if (isLast) {
                renameKeysInPlace(obj, fn);
            } else {
                for (Map.Entry<String, JsonElement> e : new ArrayList<>(obj.entrySet())) {
                    walk(e.getValue(), tokens, idx + 1, fn);
                }
            }
            return;
        }

        // Plain key descent.
        if (!node.isJsonObject()) return;
        JsonObject obj = node.getAsJsonObject();
        if (!obj.has(token)) return;
        JsonElement child = obj.get(token);
        if (isLast) {
            if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                String mapped = applySafe(fn, child.getAsString());
                if (!Objects.equals(mapped, child.getAsString())) {
                    obj.add(token, new JsonPrimitive(mapped));
                }
            }
        } else {
            walk(child, tokens, idx + 1, fn);
        }
    }

    /** Read-only twin of {@link #walk}: feeds matched leaves to {@code visitor}. */
    private static void walkCollect(JsonElement node, List<String> tokens, int idx, Consumer<String> visitor) {
        if (node == null || node.isJsonNull()) return;
        if (idx >= tokens.size()) return;

        String token = tokens.get(idx);
        boolean isLast = (idx == tokens.size() - 1);

        if (STAR_INDEX.equals(token)) {
            if (!node.isJsonArray()) return;
            JsonArray arr = node.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement elem = arr.get(i);
                if (isLast) {
                    if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                        visitor.accept(elem.getAsString());
                    }
                } else {
                    walkCollect(elem, tokens, idx + 1, visitor);
                }
            }
            return;
        }

        if (STAR_KEY.equals(token)) {
            if (!node.isJsonObject()) return;
            JsonObject obj = node.getAsJsonObject();
            if (isLast) {
                for (String k : obj.keySet()) visitor.accept(k);
            } else {
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    walkCollect(e.getValue(), tokens, idx + 1, visitor);
                }
            }
            return;
        }

        if (!node.isJsonObject()) return;
        JsonObject obj = node.getAsJsonObject();
        if (!obj.has(token)) return;
        JsonElement child = obj.get(token);
        if (isLast) {
            if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                visitor.accept(child.getAsString());
            }
        } else {
            walkCollect(child, tokens, idx + 1, visitor);
        }
    }

    /** Translate every key of a map in place (e.g. {@code openedFiles}). */
    private static void renameKeysInPlace(JsonObject obj, UnaryOperator<String> fn) {
        if (obj == null) return;
        List<String> oldKeys = new ArrayList<>(obj.keySet());
        for (String k : oldKeys) {
            String newK = applySafe(fn, k);
            if (!Objects.equals(k, newK)) {
                JsonElement v = obj.remove(k);
                obj.add(newK, v);
            }
        }
    }

    private static String applySafe(UnaryOperator<String> fn, String input) {
        if (input == null) return null;
        try {
            String out = fn.apply(input);
            return out != null ? out : input;
        } catch (Exception e) {
            return input;
        }
    }
}
