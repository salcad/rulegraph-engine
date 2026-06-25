package com.interopera.rulegraph.ingestion;

/** Raised when a source document cannot be read or parsed. */
public class IngestionException extends RuntimeException {
    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }

    public IngestionException(String message) {
        super(message);
    }
}
