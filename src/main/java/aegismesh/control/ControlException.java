package aegismesh.control;

/** A control-plane request failed. {@link #invalid()} distinguishes a bad request (HTTP 400) from unavailability (503). */
public final class ControlException extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean invalid;

    public ControlException(String message, boolean invalid) {
        super(message);
        this.invalid = invalid;
    }

    public boolean invalid() {
        return invalid;
    }
}
