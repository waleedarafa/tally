package com.tallyaccess.partner;

/** A notice Access refuses, with the reason (ADR-056). */
public class InvalidNoticeException extends RuntimeException {

    public InvalidNoticeException(String reason) {
        super(reason);
    }
}
