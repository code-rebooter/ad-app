package com.smart.android.adsdk.internal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

final class SdkHttpLoggingInterceptor implements Interceptor {
    private static final AtomicLong IDS = new AtomicLong();
    private static final long MAX_RESPONSE_BYTES = 1_048_576L;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!SdkLog.isEnabled()) {
            return chain.proceed(request);
        }
        String label = "httpId=" + IDS.incrementAndGet() + " " + request.method() + " " + request.url();
        long started = System.nanoTime();
        SdkLog.i("AdSdkHttp", "--> " + label + " body=" + requestBody(request));
        try {
            Response response = chain.proceed(request);
            SdkLog.i("AdSdkHttp", "<-- " + label + " status=" + response.code()
                + " message=" + response.message() + " elapsedMs=" + ((System.nanoTime() - started) / 1_000_000L));
            ResponseBody body = response.body();
            return body == null ? response : response.newBuilder().body(recordBody(label, body)).build();
        } catch (IOException error) {
            SdkLog.e("AdSdkHttp", "<-- " + label + " exception=" + error, error);
            throw error;
        }
    }

    private ResponseBody recordBody(String label, ResponseBody body) {
        Buffer captured = new Buffer();
        BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
            private long observedBytes;
            private boolean logged;

            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                long offset = sink.size();
                try {
                    long count = super.read(sink, byteCount);
                    if (count > 0) {
                        long keep = Math.min(count, MAX_RESPONSE_BYTES - captured.size());
                        if (keep > 0) sink.copyTo(captured, offset, keep);
                        observedBytes += count;
                    }
                    if (count == -1 || (body.contentLength() >= 0 && observedBytes == body.contentLength())) {
                        logBody(true);
                    }
                    return count;
                } catch (IOException error) {
                    SdkLog.e("AdSdkHttp", label + " response read failed", error);
                    logBody(false);
                    throw error;
                }
            }

            @Override
            public void close() throws IOException {
                logBody(body.contentLength() >= 0 && observedBytes == body.contentLength());
                super.close();
            }

            private void logBody(boolean complete) {
                if (logged) return;
                logged = true;
                try {
                    MediaType type = body.contentType();
                    Charset charset = type == null ? StandardCharsets.UTF_8 : type.charset(StandardCharsets.UTF_8);
                    String text = captured.clone().readString(charset);
                    String suffix = observedBytes > MAX_RESPONSE_BYTES ? " [body exceeds 1 MiB; truncated]" : "";
                    if (!complete) suffix += " [body not fully consumed by caller]";
                    SdkLog.i("AdSdkHttp", label + " responseBody=" + text + suffix);
                } catch (RuntimeException error) {
                    SdkLog.w("AdSdkHttp", label + " unable to format response for logging", error);
                }
            }
        });
        return new ResponseBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }
            @Override public BufferedSource source() { return source; }
        };
    }

    private String requestBody(Request request) {
        RequestBody body = request.body();
        if (body == null) return "";
        if (body.isDuplex() || body.isOneShot()) return "[non-repeatable body]";
        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            MediaType type = body.contentType();
            return buffer.readString(type == null ? StandardCharsets.UTF_8 : type.charset(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            return "[unable to read body: " + error + "]";
        }
    }
}
