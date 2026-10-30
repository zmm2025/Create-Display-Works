package com.zmm2025.createdisplayworks.media;

import java.io.IOException;

/** An import failure with a known cause, so the screen can explain it without parsing the message. */
public final class MediaImportException extends IOException {
    public enum Reason { NOT_FOUND, TOO_LARGE, TIMED_OUT }
    private final Reason reason;
    public MediaImportException(Reason reason,String message){super(message);this.reason=reason;}
    public Reason reason(){return reason;}
}
