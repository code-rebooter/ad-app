package com.smart.android.googlevideoad;

public final class AdRequest {
    private final boolean soundEnabled;

    private AdRequest(Builder builder) {
        this.soundEnabled = builder.soundEnabled;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public static final class Builder {
        private boolean soundEnabled;

        public Builder setSoundEnabled(boolean soundEnabled) {
            this.soundEnabled = soundEnabled;
            return this;
        }

        public AdRequest build() {
            return new AdRequest(this);
        }
    }
}
