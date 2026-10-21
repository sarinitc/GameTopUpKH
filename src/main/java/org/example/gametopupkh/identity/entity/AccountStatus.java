package org.example.gametopupkh.identity.entity;

/**
 * Lifecycle of a user account. Only {@link #ACTIVE} accounts can authenticate.
 */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    DISABLED,
    PENDING_VERIFICATION
}
