package com.smart.android.googleima;

/** Resolves the Core's complete ad URL on a network worker before advertising HTTP. */
public interface GoogleImaAdUrlResolver {
    boolean handles(String url);

    String resolve(String url) throws Exception;

    long remainingTimeMs();

    /** Cancel outstanding work when this player's request is released or replaced. */
    void cancel();
}
