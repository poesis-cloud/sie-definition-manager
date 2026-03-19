package cloud.poesis.sie.defman.exception;

import java.util.Map;

/**
 * Exception representing an unexpected internal failure in the GSM Definition
 * Manager.
 *
 * <p>This exception should be thrown when an error cannot be mapped to a more
 * specific domain exception (for example, a GSM rule-violation or a
 * resource-not-found condition) but still needs to be exposed as a structured
 * {@code ProblemDetail} with an internal-error type.</p>
 *
 * <p>The corresponding HTTP response is typically a {@code 500 Internal Server
 * Error} with the {@code type} set to {@code gsm:exceptions/internal-error}.</p>
 *
 * @since 0.1.0
 */
public class GsmInternalException extends RuntimeException {

    private final String type;

    public GsmInternalException(String detail) {

    /**
     * Returns the GSM-specific error type identifier used when this exception
     * is rendered as an RFC 7807 {@code ProblemDetail} response.
     * <p>
     * The returned value is mapped to the {@code type} field of the
     * {@code ProblemDetail}, allowing clients to distinguish this internal
     * GSM error from other problem types.
     *
     * @return the stable GSM error type identifier for this internal error
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the human-readable problem title used for
     * {@code ProblemDetail} responses derived from this exception.
     * <p>
     * The returned value is mapped to the {@code title} field of the
     * {@code ProblemDetail} and is intended for display to users and
     * operators as a concise description of the error category.
     *
     * @return the generic title describing this internal server error
     */
    public String getTitle() {
        return "Internal server error";
    }

    /**
     * Returns additional key-value pairs to include in the
     * {@code ProblemDetail} {@code extensions} map when this exception is
     * converted to a structured error response.
     * <p>
     * For {@code GsmInternalException}, no extra fields are added, so this
     * method currently returns an empty, immutable map.
     *
     * @return a map of {@code ProblemDetail} extension attributes, or an
     *         empty map when no extensions are defined
     */
    public String getType() {
        return type;
    }

    public String getTitle() {
        return "Internal server error";
    }

    public Map<String, Object> getExtensions() {
        return Map.of();
    }
}
