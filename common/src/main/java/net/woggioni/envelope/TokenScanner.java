package net.woggioni.envelope;

import lombok.Getter;

public class TokenScanner {

    public enum TokenType {
        ESCAPE, TOKEN, END;
    }
    private final String haystack;
    private final char needle;
    private final char escape;
    private int begin;
    private final int end;

    @Getter
    private int tokenIndex = -1;

    @Getter
    private TokenType tokenType = null;

    public TokenScanner(String haystack, char needle, char escape, int begin, int end) {
        this.haystack = haystack;
        this.needle = needle;
        this.escape = escape;
        this.begin = begin;
        this.end = end;
    }

    public TokenScanner(String haystack, char needle, char escape, int begin) {
        this(haystack, needle, escape, begin, haystack.length());
    }

    public TokenScanner(String haystack, char needle, char escape) {
        this(haystack, needle, escape, 0, haystack.length());
    }

    public void next() {
        int result = -1;
        int cursor = begin;
        int escapeCount = 0;
        while(true) {
            if(cursor < end) {
                char c = haystack.charAt(cursor);
                if (escapeCount > 0) {
                    --escapeCount;
                    if(c == escape || c == needle) {
                        tokenIndex = cursor - 1;
                        tokenType = TokenType.ESCAPE;
                        break;
                    }
                } else if (escapeCount == 0) {
                    if (c == escape) {
                        ++escapeCount;
                    }
                    if (c == needle) {
                        result = cursor;
                    }
                }
                if (result >= 0 && escapeCount == 0) {
                    tokenIndex = result;
                    tokenType = TokenType.TOKEN;
                    break;
                }
                ++cursor;
            } else {
                tokenIndex = result;
                tokenType = result < 0 ? TokenType.END :TokenType.TOKEN;
                break;
            }
        }
        begin = cursor + 1;
    }
}