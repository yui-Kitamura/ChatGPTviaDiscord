package app.viadiscord;

enum ResponseMode {
    THREAD("thread"), QA("qa");

    private final String value;
    ResponseMode(String value) { this.value = value; }
    String value() { return value; }

    static ResponseMode parse(String value) {
        for (var mode : values()) if (mode.value.equals(value)) return mode;
        throw new IllegalArgumentException("Mode must be thread or qa.");
    }
}
