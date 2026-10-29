package com.smart.android.googleima;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable runtime observation emitted by the controlled IMA integration. */
public final class GoogleImaAuditEvent {
    private final String eventType;
    private final long elapsedRealtimeMs;
    private final Map<String, String> fields;
    private final String payload;

    public GoogleImaAuditEvent(
            String eventType,
            long elapsedRealtimeMs,
            Map<String, String> fields,
            String payload) {
        this.eventType = eventType == null ? "" : eventType;
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.fields = Collections.unmodifiableMap(
                new LinkedHashMap<>(fields == null ? Collections.emptyMap() : fields));
        this.payload = payload;
    }

    public String getEventType() {
        return eventType;
    }

    public long getElapsedRealtimeMs() {
        return elapsedRealtimeMs;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public String getPayload() {
        return payload;
    }
}
