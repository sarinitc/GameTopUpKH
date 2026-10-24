package org.example.gametopupkh.audit.enums;

/**
 * Kind of administrative action recorded in the audit trail. The resource is
 * described separately by the log's entity type and entity id.
 */
public enum AuditAction {

    CREATE,
    UPDATE,
    DELETE,
    STATUS_CHANGE,
    RETRY,
    RECONCILE,
    REFUND,
    LOGIN,
    LOGOUT;
}
