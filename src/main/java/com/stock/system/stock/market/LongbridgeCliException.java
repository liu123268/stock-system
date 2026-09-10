package com.stock.system.stock.market;

class LongbridgeCliException extends RuntimeException {

    private final String providerCode;

    LongbridgeCliException(String message) {
        this(null, message, null);
    }

    LongbridgeCliException(String message, Throwable cause) {
        this(null, message, cause);
    }

    LongbridgeCliException(String providerCode, String message) {
        this(providerCode, message, null);
    }

    private LongbridgeCliException(String providerCode, String message, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
    }

    boolean hasProviderCode(String expected) {
        return expected.equals(providerCode);
    }
}
