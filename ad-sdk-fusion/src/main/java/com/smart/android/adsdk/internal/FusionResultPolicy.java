package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdResultStatus;

final class FusionResultPolicy {
    private FusionResultPolicy() {}

    static AdResult combine(AdResult google, AdResult tcl) {
        AdResult[] results = {google, tcl};
        for (AdResult r : results) {
            if (r != null && r.getStatus() == AdResultStatus.COMPLETED) return r;
        }
        for (AdResult r : results) {
            if (r != null && r.getStatus() == AdResultStatus.ERROR) return r;
        }
        // Preserve the legacy SKIPPED-with-error contract, including the exact error object.
        for (AdResult r : results) {
            if (r != null && r.getError() != null) return r;
        }
        return google != null ? google : tcl;
    }
}
