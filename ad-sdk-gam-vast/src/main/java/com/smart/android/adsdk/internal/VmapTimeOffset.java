package com.smart.android.adsdk.internal;

import java.util.Objects;

final class VmapTimeOffset {
    enum Type {
        START,
        END,
        ABSOLUTE,
        PERCENTAGE,
        UNSCHEDULED
    }

    private final Type type;
    private final long offsetMs;
    private final float percentage;

    private VmapTimeOffset(Type type, long offsetMs, float percentage) {
        this.type = type;
        this.offsetMs = offsetMs;
        this.percentage = percentage;
    }

    static VmapTimeOffset start() {
        return new VmapTimeOffset(Type.START, 0L, -1f);
    }

    static VmapTimeOffset end() {
        return new VmapTimeOffset(Type.END, -1L, -1f);
    }

    static VmapTimeOffset absolute(long offsetMs) {
        return new VmapTimeOffset(Type.ABSOLUTE, Math.max(0L, offsetMs), -1f);
    }

    static VmapTimeOffset percentage(float percentage) {
        return new VmapTimeOffset(Type.PERCENTAGE, -1L, percentage);
    }

    static VmapTimeOffset unscheduled() {
        return new VmapTimeOffset(Type.UNSCHEDULED, -1L, -1f);
    }

    Type getType() {
        return type;
    }

    long getOffsetMs() {
        return offsetMs;
    }

    float getPercentage() {
        return percentage;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VmapTimeOffset)) {
            return false;
        }
        VmapTimeOffset that = (VmapTimeOffset) other;
        return offsetMs == that.offsetMs
            && Float.compare(that.percentage, percentage) == 0
            && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, offsetMs, percentage);
    }

    @Override
    public String toString() {
        return "VmapTimeOffset{"
            + "type=" + type
            + ", offsetMs=" + offsetMs
            + ", percentage=" + percentage
            + '}';
    }
}
