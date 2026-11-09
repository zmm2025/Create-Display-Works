package com.zmm2025.createdisplayworks.media;

import java.io.IOException;

/** An import failure with a known cause, so the screen can explain it without parsing the message. */
public final class MediaImportException extends IOException {
    public enum Reason {
        NOT_FOUND,
        TOO_LARGE,
        TIMED_OUT,
        /** The media link or YouTube couldn't be reached. */
        NETWORK,
        /** A one-time helper download failed; {@link #detail()} names the host it comes from. */
        TOOLS_DOWNLOAD,
        /** A downloaded helper couldn't start, for example because security software blocked it. */
        TOOLS_BLOCKED,
        /** The player turned media tools off in the client config. */
        TOOLS_OFF,
        /** The player chose not to download the media tools this import needs. */
        DECLINED,
        /** No helpers exist for this OS and CPU; {@link #detail()} names the platform. */
        UNSUPPORTED,
        /** The helper and scratch folder can't be written; {@link #detail()} is its path. */
        CACHE
    }

    private final Reason reason;
    private final String detail;

    public MediaImportException(Reason reason, String message) {
        this(reason, message, "", null);
    }

    public MediaImportException(Reason reason, String message, String detail, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.detail = detail == null ? "" : detail;
    }

    public Reason reason() {
        return reason;
    }
    /** A host, platform or path the player-facing message shows; empty when it has none. */
    public String detail() {
        return detail;
    }
}
