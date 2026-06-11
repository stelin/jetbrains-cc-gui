package com.github.claudecodegui.provider.claude;

import com.google.gson.JsonParser;

/**
 * Shared helpers for parsing mixed stdout output from Node.js bridge commands.
 */
class ClaudeJsonOutputExtractor {

    String extractBetween(String text, String start, String end) {
        int startIdx = text.indexOf(start);
        if (startIdx == -1) {
            return null;
        }
        startIdx += start.length();

        int endIdx = text.indexOf(end, startIdx);
        if (endIdx == -1) {
            return null;
        }

        return text.substring(startIdx, endIdx);
    }

    String extractLastJsonLine(String outputStr) {
        if (outputStr == null || outputStr.isEmpty()) {
            return null;
        }

        String[] lines = outputStr.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}") && isJsonObject(line)) {
                return line;
            }
        }

        return extractLastCompleteJsonObject(outputStr);
    }

    private String extractLastCompleteJsonObject(String outputStr) {
        String last = null;
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < outputStr.length(); i++) {
            char ch = outputStr.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    String candidate = outputStr.substring(start, i + 1).trim();
                    if (isJsonObject(candidate)) {
                        last = candidate;
                    }
                    start = -1;
                }
            }
        }
        return last;
    }

    private boolean isJsonObject(String text) {
        try {
            return JsonParser.parseString(text).isJsonObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    String extractErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }

        Throwable current = throwable;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && !msg.trim().isEmpty()) {
                return msg;
            }
            current = current.getCause();
        }
        return throwable.getClass().getSimpleName();
    }
}
