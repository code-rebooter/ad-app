package com.smart.android.adsdk.internal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import java.util.IdentityHashMap;
import java.util.Map;

/** Owns only the SDK's child view. Host background, alpha and visibility stay host-owned. */
final class TclDisplayLayer extends FrameLayout {
    private final ViewGroup host;
    private final boolean hidden;
    private final AdPlayer.Listener listener;
    private final Map<TextureView, FrameListener> frameListeners = new IdentityHashMap<>();
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> { attachFrameListeners(this); return true; };
    private boolean started, firstFrame, disposed, releasing;

    TclDisplayLayer(Context context, ViewGroup host, boolean hidden, AdPlayer.Listener listener) {
        super(context);
        this.host = host;
        this.hidden = hidden;
        this.listener = listener;
        setBackgroundColor(Color.TRANSPARENT);
        setAlpha(0f);
        setClipChildren(true);
        host.addView(this, new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        host.getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    void markStarted() {
        started = true;
        attachFrameListeners(this);
        applyVisibility();
    }

    private void applyVisibility() {
        if (!disposed && !releasing) setAlpha(!hidden && started && firstFrame ? 1f : 0f);
    }

    private void attachFrameListeners(View view) {
        if (disposed) return;
        if (view instanceof TextureView) {
            TextureView texture = (TextureView) view;
            FrameListener existing = frameListeners.get(texture);
            if (existing == null || texture.getSurfaceTextureListener() != existing) {
                FrameListener wrapper = new FrameListener(texture.getSurfaceTextureListener());
                frameListeners.put(texture, wrapper);
                texture.setSurfaceTextureListener(wrapper);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) attachFrameListeners(group.getChildAt(i));
        }
    }

    void hideForRelease() { releasing = true; setAlpha(0f); }

    void dispose() {
        if (disposed) return;
        disposed = true;
        setAlpha(0f);
        if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        for (Map.Entry<TextureView, FrameListener> entry : frameListeners.entrySet()) {
            if (entry.getKey().getSurfaceTextureListener() == entry.getValue()) {
                entry.getKey().setSurfaceTextureListener(entry.getValue().delegate);
            }
        }
        frameListeners.clear();
        host.removeView(this);
        removeAllViews();
    }

    private final class FrameListener implements TextureView.SurfaceTextureListener {
        final TextureView.SurfaceTextureListener delegate;
        FrameListener(TextureView.SurfaceTextureListener delegate) { this.delegate = delegate; }
        @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
            if (delegate != null) delegate.onSurfaceTextureAvailable(s, w, h);
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
            if (delegate != null) delegate.onSurfaceTextureSizeChanged(s, w, h);
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
            return delegate == null || delegate.onSurfaceTextureDestroyed(s);
        }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {
            if (delegate != null) delegate.onSurfaceTextureUpdated(s);
            if (disposed || firstFrame) return;
            firstFrame = true;
            listener.onTrace("AD_FIRST_FRAME", "provider=TCL hiddenMode=" + hidden);
            applyVisibility();
        }
    }
}
