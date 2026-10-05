package org.hyzionstudios.mysticquests.studio;

/**
 * A Studio request that cannot be carried out, with the reason shown to the creator. The status maps
 * onto an HTTP status in the web layer; the message never contains file system paths or secrets.
 */
public final class StudioException extends Exception {
    public enum Status {
        BAD_REQUEST(400),
        UNAUTHORIZED(401),
        FORBIDDEN(403),
        NOT_FOUND(404),
        /** The draft or live content changed underneath the request, or validation failed. */
        CONFLICT(409),
        TOO_LARGE(413),
        TOO_MANY_REQUESTS(429);

        private final int http;

        Status(int http) {
            this.http = http;
        }

        public int http() {
            return http;
        }
    }

    private final Status status;
    private final transient Object detail;

    public StudioException(Status status, String message) {
        this(status, message, null);
    }

    /** @param detail structured data the client can show, such as a failed validation */
    public StudioException(Status status, String message, Object detail) {
        super(message);
        this.status = status;
        this.detail = detail;
    }

    public Status status() {
        return status;
    }

    public Object detail() {
        return detail;
    }
}
