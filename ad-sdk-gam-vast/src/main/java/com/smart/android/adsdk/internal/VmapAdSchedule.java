package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VmapAdSchedule {
    private final List<VmapAdBreak> breaks;

    VmapAdSchedule(List<VmapAdBreak> breaks) {
        if (breaks == null || breaks.isEmpty()) {
            this.breaks = Collections.emptyList();
        } else {
            this.breaks = Collections.unmodifiableList(new ArrayList<>(breaks));
        }
    }

    List<VmapAdBreak> getBreaks() {
        return breaks;
    }
}
