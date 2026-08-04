package com.smart.android.hq008flow.internal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

final class SdkHttpLoggingInterceptor implements Interceptor {
    private static final String TAG = "Hq008FlowApi";
    private static final int MAX_LOG_CHARS = 4_000;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!SdkLog.isEnabled()) {
            return chain.proceed(request);
        }

        long startedAtNs = System.nanoTime();
        SdkLog.i(
                TAG,
                "--> " + request.method()
                        + " " + request.url()
                        + " body=" + shorten(readRequestBody(request))
        );
        try {
            Response response = chain.proceed(request);
            long tookMs = (System.nanoTime() - startedAtNs) / 1_000_000L;
            String body = shorten(response.peekBody(64L * 1024L).string());
            SdkLog.i(
                    TAG,
                    "<-- " + response.code()
                            + " " + request.method()
                            + " " + request.url()
                            + " (" + tookMs + "ms)"
                            + " body=" + body
            );
            return response;
        } catch (IOException error) {
            long tookMs = (System.nanoTime() - startedAtNs) / 1_000_000L;
            SdkLog.w(
                    TAG,
                    "<-- HTTP FAILED " + request.method()
                            + " " + request.url()
                            + " (" + tookMs + "ms): "
                            + safeMessage(error),
                    error
            );
            throw error;
        }
    }

    private String readRequestBody(Request request) {
        RequestBody body = request.body();
        if (body == null) {
            return "";
        }
        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            Charset charset = StandardCharsets.UTF_8;
            MediaType contentType = body.contentType();
            if (contentType != null) {
                Charset candidate = contentType.charset(StandardCharsets.UTF_8);
                if (candidate != null) {
                    charset = candidate;
                }
            }
            return buffer.readString(charset);
        } catch (Throwable error) {
            return "<unreadable request body: " + safeMessage(error) + ">";
        }
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_LOG_CHARS) {
            return text;
        }
        return text.substring(0, MAX_LOG_CHARS) + "...(truncated)";
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
