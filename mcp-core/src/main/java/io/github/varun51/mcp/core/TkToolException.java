package io.github.varun51.mcp.core;

public final class TkToolException extends RuntimeException {

    public TkToolException(String tkMessage) {
        super(tkMessage);
    }

    public TkToolException(String tkMessage, Throwable tkCause) {
        super(tkMessage, tkCause);
    }

    public static void mtkCheck(boolean tkCondition, String tkMessage) {
        if (!tkCondition) {
            throw new TkToolException(tkMessage);
        }
    }
}
