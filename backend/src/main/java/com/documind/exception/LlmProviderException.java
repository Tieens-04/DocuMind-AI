package com.documind.exception;

/**
 * Raised when primary and configured fallback LLM chat providers fail or return an invalid response.
 */
public class LlmProviderException extends RuntimeException {

    private final String provider;

    public LlmProviderException(String message) {
        this(null, message);
    }

    public LlmProviderException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public LlmProviderException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public LlmProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }
}
