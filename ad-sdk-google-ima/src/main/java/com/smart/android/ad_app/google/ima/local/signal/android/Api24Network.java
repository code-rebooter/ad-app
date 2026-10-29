package com.smart.android.ad_app.google.ima.local.signal.android;
import android.net.ConnectivityManager;
/** Loaded only after the preserved effective SDK>=24 gate. */
final class Api24Network {static void register(ConnectivityManager manager,ConnectivityManager.NetworkCallback callback){manager.registerDefaultNetworkCallback(callback);}}
