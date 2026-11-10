package br.com.datum.notification.application;

public enum NotificationDecision {
    NOTIFICATION_REGISTERED, DUPLICATE, EVENT_ID_CONFLICT;

    public boolean accepted() {
        return this != EVENT_ID_CONFLICT;
    }
}
