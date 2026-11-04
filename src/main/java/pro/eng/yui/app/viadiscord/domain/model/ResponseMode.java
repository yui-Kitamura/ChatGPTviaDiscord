package pro.eng.yui.app.viadiscord.domain.model;

public enum ResponseMode {

    THREAD("thread"), QA("qa");

    private final String value;

    ResponseMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ResponseMode parse(String value) {
        for (var mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Mode must be thread or qa.");
    }
}
