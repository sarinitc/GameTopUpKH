package org.example.gametopupkh.identity.bootstrap;

/** Result of one invocation of the one-time ADMIN bootstrap. */
public enum AdminBootstrapOutcome {

    /** The bootstrap is not enabled; no account was inspected or changed. */
    DISABLED,

    /** The selected account already had the ADMIN role; nothing was changed. */
    ALREADY_ADMIN,

    /** The selected account was granted ADMIN and its existing sessions were invalidated. */
    GRANTED
}
