package com.interopera.rulegraph.firmconfig;

/** Raised when a firm configuration cannot be found, parsed, or validated. */
public class FirmConfigException extends RuntimeException {

    public FirmConfigException(String message) {
        super(message);
    }

    public FirmConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
