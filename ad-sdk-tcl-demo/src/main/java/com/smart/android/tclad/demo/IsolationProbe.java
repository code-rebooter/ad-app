package com.smart.android.tclad.demo;

import android.content.Context;
import com.tcl.ff.component.overseahttp.http.HttpRequester;
import com.tcl.ff.component.overseabasebusiness.AdConfigFetcher;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONObject;
import org.json.JSONArray;

/** Probe TCL business boundaries and preserved local player storage; no real advertising request. */
final class IsolationProbe {
    static void run(Context context, NetworkAudit audit) {
        try {
            JSONObject report = new JSONObject();
            for (String name : new String[]{"com.tcl.ff.component.adsdkbi.DataReport", "com.tcl.ff.component.overseabase.database.LitePal", "a.a.a.a.a.a.b"}) {
                try { Class.forName(name); throw new IllegalStateException("Forbidden class available: "+name); }
                catch (ClassNotFoundException expected) { report.put(name, "absent"); }
            }
            com.tcl.ff.component.overseabasebusiness.bi.BiInitialization.init(context);
            com.tcl.ff.component.overseabasebusiness.bi.BiReportUtil.reportAdConfigRequest();
            com.tcl.ff.component.overseabasebusiness.bi.BiReportUtil.reportAdConfigResult();
            if (HttpRequester.get().resolveSuppliedAdAddress("https://not-an-ad.invalid/business-address", "{}") != null)
                throw new IllegalStateException("TCL address boundary returned an unsupplied response");
            report.put("tclAddressNetworkBoundary", "blocked");
            File fixture = new File(context.getFilesDir(), "scope-file-fixture.txt");
            try {
                if (!com.tcl.ff.component.overseabase.base.util.FileUtils.writeToFile(fixture,"file-round-trip")) throw new IllegalStateException("Original file write failed");
                try (java.io.FileInputStream in = new java.io.FileInputStream(fixture)) {
                    var fileBytes = new java.io.ByteArrayOutputStream();
                    byte[] fileBuffer = new byte[64]; int fileCount;
                    while ((fileCount = in.read(fileBuffer)) != -1) fileBytes.write(fileBuffer,0,fileCount);
                    if (!"file-round-trip".equals(new String(fileBytes.toByteArray(), StandardCharsets.UTF_8))) throw new IllegalStateException("Original file data changed");
                }
                boolean deleteResult=com.tcl.ff.component.overseabase.base.util.FileUtils.deleteFile(fixture);
                if (fixture.exists()) throw new IllegalStateException("Original file delete failed");
                // The pinned implementation deletes the file but returns false; preserve it.
                report.put("originalFileDeleteReturnValue",deleteResult);
            } finally { fixture.delete(); }
            report.put("genericFileUtility", "write-read-delete-passed");
            File cacheDirectory = new File(context.getCacheDir(), "scope-media-cache-fixture");
            var cache = new com.google.android.exoplayer2.upstream.cache.SimpleCache(cacheDirectory,new com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor());
            byte[] mediaBytes="cached-media-probe".getBytes(StandardCharsets.UTF_8);
            var spec = new com.google.android.exoplayer2.upstream.DataSpec.Builder().setUri("https://media-cache-probe.invalid/file.mp4").setKey("scope-media").setLength(mediaBytes.length).build();
            try {
                var writer=new com.google.android.exoplayer2.upstream.cache.CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(() -> new com.google.android.exoplayer2.upstream.ByteArrayDataSource(mediaBytes)).createDataSource();
                try { writer.open(spec); byte[] buffer=new byte[64]; while(writer.read(buffer,0,buffer.length)!=-1) {} }
                finally { writer.close(); }
                if (!cache.isCached("scope-media",0,mediaBytes.length)) throw new IllegalStateException("Original cache did not write media");
                // No upstream: a cache miss must fail instead of fetching the fixture URI.
                var reader=new com.google.android.exoplayer2.upstream.cache.CacheDataSource.Factory().setCache(cache).createDataSource();
                var captured=new java.io.ByteArrayOutputStream();
                try { reader.open(spec); byte[] buffer=new byte[64]; int count; while((count=reader.read(buffer,0,buffer.length))!=-1) captured.write(buffer,0,count); }
                finally { reader.close(); }
                if (!java.util.Arrays.equals(mediaBytes,captured.toByteArray())) throw new IllegalStateException("Original cached media differs");
            } finally { cache.release(); com.google.android.exoplayer2.upstream.cache.SimpleCache.delete(cacheDirectory,null); }
            if (cacheDirectory.exists()) throw new IllegalStateException("Cannot clean up media cache fixture");
            report.put("mediaCache", "write-and-cache-hit-read-passed");
            var provider = new com.google.android.exoplayer2.database.StandaloneDatabaseProvider(context);
            try {
                var database=provider.getWritableDatabase();
                database.execSQL("CREATE TABLE IF NOT EXISTS scope_probe(value TEXT)");
                database.execSQL("DELETE FROM scope_probe");
                database.execSQL("INSERT INTO scope_probe VALUES ('media-index')");
                var delegated = new com.google.android.exoplayer2.database.DefaultDatabaseProvider(provider);
                try (var cursor=delegated.getReadableDatabase().rawQuery("SELECT value FROM scope_probe",null)) {
                    if (!cursor.moveToFirst() || !"media-index".equals(cursor.getString(0))) throw new IllegalStateException("Original media index database read failed");
                }
                database.execSQL("DROP TABLE scope_probe");
            } finally { provider.close(); context.deleteDatabase("exoplayer_internal.db"); }
            report.put("mediaCacheDatabase", "both-original-providers-write-read-passed");
            var proxyConfig = new com.tcl.uniplayer.tuniplayer.b();
            var proxyFlag=proxyConfig.getClass().getDeclaredField("h"); proxyFlag.setAccessible(true);
            var restrictions=com.tcl.uniplayer.tuniplayer.c.d.a.a();
            var prioritize=restrictions.getClass().getDeclaredField("h"); prioritize.setAccessible(true);
            var prohibit=restrictions.getClass().getDeclaredField("i"); prohibit.setAccessible(true);
            boolean savedPrioritize=prioritize.getBoolean(restrictions), savedProhibit=prohibit.getBoolean(restrictions);
            try {
                prioritize.setBoolean(restrictions,false); prohibit.setBoolean(restrictions,false);
                proxyFlag.setBoolean(proxyConfig,true); if(!proxyConfig.b(true)) throw new IllegalStateException("Original proxy decision is still forced off");
                proxyFlag.setBoolean(proxyConfig,false);
                var initializeProxy=proxyConfig.getClass().getDeclaredMethod("k"); initializeProxy.setAccessible(true);
                if (!Boolean.TRUE.equals(initializeProxy.invoke(proxyConfig))) throw new IllegalStateException("Original inactive proxy initializer failed");
            } finally { prioritize.setBoolean(restrictions,savedPrioritize); prohibit.setBoolean(restrictions,savedProhibit); }
            report.put("optionalProxyDecision", "original-config-driven");
            try { Class.forName("com.tcl.uniplayer.cache.Proxy"); throw new IllegalStateException("Unexpected new proxy dependency"); }
            catch (ClassNotFoundException expected) { report.put("optionalProxyPlugin", "absent-from-supplied-input"); }
            var tracker = new com.tcl.tuniplayer_base.d(); tracker.a(true);
            var trackerField = tracker.getClass().getDeclaredField("c"); trackerField.setAccessible(true);
            if (trackerField.get(tracker) != null) throw new IllegalStateException("Tracker registered");
            Object mustNotFormat = new Object() { @Override public String toString() { throw new AssertionError("Vendor telemetry formatted an event"); } };
            tracker.a("probe", "value", mustNotFormat, mustNotFormat);
            tracker.a("probe", "value", "unused", (java.util.Map<String,String>)null);
            report.put("vendorTracker", "blocked");
            // The pinned Android HTTP engine installs the original session CookieManager.
            new com.tcl.tuniplayer_exo.f.a(context).a(context);
            var cookie = java.net.CookieHandler.getDefault();
            if (!(cookie instanceof java.net.CookieManager)) throw new IllegalStateException("Original player CookieManager was not installed");
            java.net.URI cookieOrigin = java.net.URI.create("https://media-cookie-probe.invalid/");
            cookie.put(cookieOrigin, java.util.Collections.singletonMap("Set-Cookie", java.util.Collections.singletonList("tcl_probe=session; Path=/")));
            var returnedCookies = cookie.get(cookieOrigin, java.util.Collections.emptyMap());
            if (!returnedCookies.toString().contains("tcl_probe=session")) throw new IllegalStateException("Original player cookie session did not round trip");
            if (cookie.get(java.net.URI.create("https://other-origin.invalid/"), java.util.Collections.emptyMap()).toString().contains("tcl_probe=session"))
                throw new IllegalStateException("Session cookie crossed origins");
            report.put("playerCookieHandler", "original-cookie-manager");
            report.put("playerCookieSessionRoundTrip", true);
            var loader = AdConfigFetcher.get();
            var field = loader.getClass().getDeclaredField("e");field.setAccessible(true);Object snapshot=field.get(loader);
            field.set(loader,null);
            try {
                loader.preloadConfig();
                var startConfigThread = loader.getClass().getDeclaredMethod("a"); startConfigThread.setAccessible(true); startConfigThread.invoke(loader);
            } finally { field.set(loader,snapshot); }
            com.tcl.ff.component.overseabasebusiness.adutils.SharedPreferenceUtil.INSTANCE.savePrefString("probe", "must-not-persist");
            if (!"".equals(new com.tcl.ff.component.overseabase.base.device.DeviceInfoGeneratorImpl(context).getAid()))
                throw new IllegalStateException("TCL generated an identifier");
            var converter = new com.tcl.ff.component.media.ima.tcl.convert.XmlConverter<com.tcl.ff.component.media.ima.tcl.xmlmodels.VAST>();
            Class<com.tcl.ff.component.media.ima.tcl.xmlmodels.VAST> vastType = com.tcl.ff.component.media.ima.tcl.xmlmodels.VAST.class;
            if (converter.convert("<VAST class=\"java.lang.ProcessBuilder\"><command><string>unused</string></command></VAST>", vastType) != null)
                throw new IllegalStateException("Non-VAST Java type accepted");
            converter.convert("<!DOCTYPE VAST SYSTEM \"http://127.0.0.1:18888/forbidden-dtd\"><VAST version=\"3.0\"/>", vastType);
            converter.convert("<!DOCTYPE VAST [<!ENTITY external SYSTEM \"http://127.0.0.1:18888/forbidden-entity\">]><VAST version=\"3.0\"><Ad><InLine><AdTitle>&external;</AdTitle></InLine></Ad></VAST>", vastType);
            report.put("nonVastJavaType", "blocked");
            report.put("xmlExternalNetworkAttempts", audit.initializationAttempts());
            JSONArray forbiddenFiles = new JSONArray();
            for (String name : context.databaseList()) if (!name.startsWith("exoplayer_internal.db")) forbiddenFiles.put("databases/"+name);
            File prefs=new File(context.getApplicationInfo().dataDir,"shared_prefs");
            for (String name : new String[]{"tcl_ad.xml","media_ad_sp.xml"})
                if (new File(prefs,name).exists()) forbiddenFiles.put("shared_prefs/"+name);
            if (new File(context.getFilesDir(),"TAdConfig0").exists()) forbiddenFiles.put("files/TAdConfig0");
            report.put("forbiddenFiles",forbiddenFiles);
            report.put("initializationNetworkAttempts",audit.initializationAttempts());
            JSONArray threads=new JSONArray();
            for (Thread thread:Thread.getAllStackTraces().keySet()) if(thread.getName().contains("BI_")) threads.put(thread.getName());
            report.put("biThreads",threads);
            if (forbiddenFiles.length()!=0 || audit.initializationAttempts()!=0 || threads.length()!=0)
                throw new IllegalStateException("Isolation side effects found: "+report);
            report.put("passed",true);
            try (FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"isolation-probe.json"))) {
                out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) { throw new IllegalStateException("Isolation probe failed",error); }
    }
}
