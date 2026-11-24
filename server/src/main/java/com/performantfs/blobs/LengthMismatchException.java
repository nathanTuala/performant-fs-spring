package com.performantfs.blobs;

import java.io.IOException;

/** A stream carried more or fewer bytes than its declared length. */
public class LengthMismatchException extends IOException {

    public LengthMismatchException(long expected, long received) {
        super("expected " + expected + " bytes, received " + received);
    }
}
