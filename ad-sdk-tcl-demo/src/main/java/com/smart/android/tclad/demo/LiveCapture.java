package com.smart.android.tclad.demo;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Explicit demo-only recording of the actual SDK requests, not a second prefetch. */
final class LiveCapture implements Interceptor {
    private final File directory;
    private String referenceResponse;
    Runnable onVastRequest;
    private final AtomicInteger sequence = new AtomicInteger();

    LiveCapture(Context context, String source) {
        directory = new File(context.getFilesDir(), "live-" + source + "-" + System.currentTimeMillis());
        if (!directory.mkdirs()) throw new IllegalStateException("Cannot create capture directory");
        android.util.Log.i("TclNativeDemo", "CAPTURE " + directory.getAbsolutePath());
    }

    void install(String referenceResponse) {
        this.referenceResponse = referenceResponse;
        try {
            var requester = com.tcl.ff.component.overseahttp.http.HttpRequester.get();
            java.lang.reflect.Field field = requester.getClass().getDeclaredField("c");
            field.setAccessible(true);
            okhttp3.OkHttpClient original = (okhttp3.OkHttpClient) field.get(requester);
            var builder = original.newBuilder();
            for (var iterator = builder.interceptors().iterator(); iterator.hasNext();) {
                if (iterator.next() instanceof LiveCapture) iterator.remove();
            }
            builder.interceptors().add(0, this);
            field.set(requester, builder.build());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    @Override public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        int id = sequence.incrementAndGet();
        String contentType = request.header("Content-Type");
        String kind = contentType != null && contentType.startsWith("application/xml") ? "vast" : "tracking";
        if (request.method().equals("POST")) kind = "upstream";
        long start = SystemClock.elapsedRealtime();
        write(id, "request", request.url().toString(), kind, 0, "", 0);
        if ("vast".equals(kind) && onVastRequest != null) onVastRequest.run();
        try {
            Response response;
            if (referenceResponse != null && request.method().equals("POST")
                && request.url().encodedPath().contains("getWaterfall")) {
                response = new Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(referenceResponse, okhttp3.MediaType.get("application/json"))).build();
            } else response = chain.proceed(request);
            String filename = "";
            if ("vast".equals(kind) && response.body() != null) {
                filename = String.format(java.util.Locale.US, "vast-%03d.xml", id);
                // peekBody leaves the response body available for the SDK's real VAST parser.
                try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
                    output.write(response.peekBody(1_048_576).bytes());
                } catch (IOException error) { android.util.Log.w("TclNativeDemo", "CAPTURE_XML_FAILED", error); }
            }
            write(id, "response", response.request().url().toString(), kind, response.code(), filename,
                SystemClock.elapsedRealtime() - start);
            return response;
        } catch (IOException error) {
            write(id, "failure", request.url().toString(), kind, 0, error.toString(), SystemClock.elapsedRealtime() - start);
            throw error;
        }
    }

    void event(String event) { write(0, "callback", "", event, 0, "", 0); }

    private synchronized void write(int id, String phase, String url, String kind, int status, String detail, long elapsed) {
        try {
            JSONObject row = new JSONObject();
            row.put("timeMs", System.currentTimeMillis()).put("id", id).put("phase", phase)
                .put("kind", kind).put("url", url).put("status", status).put("detail", detail).put("elapsedMs", elapsed);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "events.jsonl"), true)) {
                output.write((row + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!"request".equals(phase)) android.util.Log.i("TclLiveHttp", phase + " " + kind + " status=" + status + " id=" + id);
        } catch (Exception error) { android.util.Log.w("TclNativeDemo", "CAPTURE_EVENT_FAILED", error); }
    }
}
