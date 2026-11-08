package com.smart.android.googleima;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assigns stable local IDs while keeping duplicate creatives as separate ad instances. */
final class GoogleImaAdInstanceTracker {
    private final Map<Object, String> objectIds = new IdentityHashMap<>();
    private final Map<String, String> fingerprints = new HashMap<>();
    private final Map<String, List<String>> idsByFingerprint = new HashMap<>();
    private final Set<String> terminalIds = new HashSet<>();
    private long nextSequence;
    private String currentId = "";

    String onLoaded(Object token, String fingerprint) {
        String existing = token == null ? null : objectIds.get(token);
        if (existing != null && !terminalIds.contains(existing)) {
            currentId = existing;
            return existing;
        }
        return create(token, fingerprint, true);
    }

    String resolve(Object token, String fingerprint) {
        String existing = token == null ? null : objectIds.get(token);
        if (existing != null) return existing;
        if (!currentId.isEmpty() && !terminalIds.contains(currentId)
                && sameFingerprint(currentId, fingerprint)) {
            if (token != null) objectIds.put(token, currentId);
            return currentId;
        }
        String historical = findLatestByFingerprint(fingerprint);
        if (historical != null) {
            if (token != null) objectIds.put(token, historical);
            return historical;
        }
        return create(token, fingerprint, false);
    }

    void markTerminal(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) return;
        terminalIds.add(instanceId);
    }

    boolean isTerminal(String instanceId) {
        return instanceId != null && terminalIds.contains(instanceId);
    }

    boolean isCurrent(String instanceId) {
        return instanceId != null && instanceId.equals(currentId);
    }

    void select(String instanceId) {
        currentId = instanceId == null ? "" : instanceId;
    }

    void reset() {
        objectIds.clear();
        fingerprints.clear();
        idsByFingerprint.clear();
        terminalIds.clear();
        nextSequence = 0L;
        currentId = "";
    }

    private String create(Object token, String fingerprint, boolean selectCurrent) {
        String instanceId = "ima-ad-" + (++nextSequence);
        if (token != null) objectIds.put(token, instanceId);
        String normalizedFingerprint = safe(fingerprint);
        fingerprints.put(instanceId, normalizedFingerprint);
        if (!normalizedFingerprint.isEmpty()) {
            List<String> ids = idsByFingerprint.get(normalizedFingerprint);
            if (ids == null) {
                ids = new ArrayList<>();
                idsByFingerprint.put(normalizedFingerprint, ids);
            }
            ids.add(instanceId);
        }
        if (selectCurrent) currentId = instanceId;
        return instanceId;
    }

    private String findLatestByFingerprint(String fingerprint) {
        String candidate = safe(fingerprint);
        if (candidate.isEmpty()) return null;
        List<String> ids = idsByFingerprint.get(candidate);
        if (ids == null) return null;
        for (int index = ids.size() - 1; index >= 0; index--) {
            String instanceId = ids.get(index);
            if (fingerprints.containsKey(instanceId)) return instanceId;
        }
        return null;
    }

    private boolean sameFingerprint(String instanceId, String fingerprint) {
        String known = fingerprints.get(instanceId);
        String candidate = safe(fingerprint);
        return known != null && !known.isEmpty() && known.equals(candidate);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
