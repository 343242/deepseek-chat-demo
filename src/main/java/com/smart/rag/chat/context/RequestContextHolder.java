package com.smart.rag.chat.context;

public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static RequestContext get() {
        return HOLDER.get();
    }

    public static void set(RequestContext context) {
        if (context == null) {
            clear();
        } else {
            HOLDER.set(context);
        }
    }

    public static void clear() {
        HOLDER.remove();
    }
}
