package io.github.mapepire_ibmi.mockserver;

/**
 * A failure that is reported to the client as an error response (<code>"success": false</code>) for the request
 * being processed. The exception message becomes the value of the reply's <code>error</code> field.
 */
public class MockRequestException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Create an exception whose message is sent to the client as the reply's <code>error</code> field.
     *
     * @param _message
     *            the human-readable error description
     */
    public MockRequestException(final String _message) {
        super(_message);
    }
}
