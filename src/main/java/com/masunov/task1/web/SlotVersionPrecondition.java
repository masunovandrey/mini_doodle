package com.masunov.task1.web;

public final class SlotVersionPrecondition {

    private SlotVersionPrecondition() {
    }

    public static long parse(String value) {
        if (value == null || !value.matches("\\\"[0-9]+\\\"")) {
            throw new InvalidUserRequestException("If-Match must contain a quoted slot version");
        }
        return Long.parseLong(value.substring(1, value.length() - 1));
    }

    public static String format(long version) {
        return "\"%d\"".formatted(version);
    }
}
