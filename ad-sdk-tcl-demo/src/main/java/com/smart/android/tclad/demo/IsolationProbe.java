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

/** Negative tests execute disabled API boundaries, never an actual advertising request. */
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
            if (HttpRequester.get().postJsonSync("https://not-an-ad.invalid/forbidden-sdk-post", "{}") != null)
                throw new IllegalStateException("SDK POST is active");
            var loader = AdConfigFetcher.get();
            var field = loader.getClass().getDeclaredField("e");field.setAccessible(true);Object snapshot=field.get(loader);
            field.set(loader,null);
            try { loader.preloadConfig(); } finally { field.set(loader,snapshot); }
            com.tcl.ff.component.overseabasebusiness.adutils.SharedPreferenceUtil.INSTANCE.savePrefString("probe", "must-not-persist");
            if (!"".equals(new com.tcl.ff.component.overseabase.base.device.DeviceInfoGeneratorImpl(context).getAid()))
                throw new IllegalStateException("TCL generated an identifier");
            try {
                new com.google.android.exoplayer2.database.StandaloneDatabaseProvider(context).getWritableDatabase();
                throw new IllegalStateException("Offline database opened");
            } catch (UnsupportedOperationException expected) { report.put("offlineDatabase", "blocked"); }
            var converter = new com.tcl.ff.component.media.ima.tcl.convert.XmlConverter<com.tcl.ff.component.media.ima.tcl.xmlmodels.VAST>();
            Class<com.tcl.ff.component.media.ima.tcl.xmlmodels.VAST> vastType = com.tcl.ff.component.media.ima.tcl.xmlmodels.VAST.class;
            if (converter.convert("<VAST class=\"java.lang.ProcessBuilder\"><command><string>unused</string></command></VAST>", vastType) != null)
                throw new IllegalStateException("Non-VAST Java type accepted");
            converter.convert("<!DOCTYPE VAST SYSTEM \"http://127.0.0.1:18888/forbidden-dtd\"><VAST version=\"3.0\"/>", vastType);
            converter.convert("<!DOCTYPE VAST [<!ENTITY external SYSTEM \"http://127.0.0.1:18888/forbidden-entity\">]><VAST version=\"3.0\"><Ad><InLine><AdTitle>&external;</AdTitle></InLine></Ad></VAST>", vastType);
            report.put("nonVastJavaType", "blocked");
            report.put("xmlExternalNetworkAttempts", audit.initializationAttempts());
            JSONArray forbiddenFiles = new JSONArray();
            for (String name : context.databaseList()) forbiddenFiles.put("databases/"+name);
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
