package com.smart.android.tclad;

import com.tcl.ff.component.media.ima.tcl.convert.XmlConverter;
import com.tcl.ff.component.media.ima.tcl.xmlmodels.VAST;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class IsolationTest {
    @Test public void originalVastModelsStillParseCapturedPod() throws Exception {
        Object model = new XmlConverter<VAST>().convert(WaterfallRequestTest.resource("tcl-hk-pod.xml"), VAST.class);
        assertNotNull(model);
        assertEquals(VAST.class, model.getClass());
    }
    @Test public void nonVastJavaTypesCannotBeInstantiatedByXml() {
        assertNull(new XmlConverter<VAST>().convert("<VAST class=\"java.lang.ProcessBuilder\"><command><string>unused</string></command></VAST>", VAST.class));
    }
    @Test(timeout=4000) public void externalDoctypeDoesNotMakeNetworkRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("<!ELEMENT VAST ANY>"));
            new XmlConverter<VAST>().convert("<!DOCTYPE VAST SYSTEM \""+server.url("/not-allowed")+"\"><VAST version=\"3.0\"/>", VAST.class);
            assertEquals(0, server.getRequestCount());
        }
    }
    @Test public void telemetryAndTclDatabaseImplementationsAreAbsent() throws Exception {
        for (String name : new String[]{"com.tcl.ff.component.adsdkbi.DataReport", "com.tcl.ff.component.adsdkbi.ReportHelper",
                "com.tcl.ff.component.overseabase.database.LitePal", "a.a.a.a.a.a.b"}) {
            try { Class.forName(name, false, getClass().getClassLoader()); fail("Forbidden class remains: "+name); }
            catch (ClassNotFoundException expected) { /* physically unavailable */ }
        }
    }
    @Test public void statisticsFacadesCannotRestartRemovedTelemetry() {
        com.tcl.ff.component.overseabasebusiness.bi.BiInitialization.init(null);
        com.tcl.ff.component.overseabasebusiness.bi.BiReportUtil.reportAdConfigRequest();
        com.tcl.ff.component.overseabasebusiness.bi.BiReportUtil.reportAdConfigResult();
        com.tcl.ff.component.overseabasebusiness.adutils.SharedPreferenceUtil.INSTANCE.savePrefString("test", "value");
        assertEquals("", com.tcl.ff.component.overseabasebusiness.adutils.SharedPreferenceUtil.INSTANCE.getPrefStringValue("test"));
    }
    @Test(expected=IllegalArgumentException.class) public void alternateSdkEnginesCannotBeSelectedByResponseMetadata() {
        ResolvedAd.fromTclResponse("{\"code\":100000,\"data\":{\"adsUrl\":\"https://ads.example/tag\",\"beaconingType\":1}}");
    }
    @Test(timeout=4000) public void externalEntityDoesNotMakeNetworkRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("must-not-be-read"));
            new XmlConverter<VAST>().convert("<!DOCTYPE VAST [<!ENTITY external SYSTEM \""+server.url("/blocked-entity")+"\">]><VAST version=\"3.0\"><Ad><InLine><AdTitle>&external;</AdTitle></InLine></Ad></VAST>", VAST.class);
            assertEquals(0,server.getRequestCount());
        }
    }
    @Test public void sdkPostMethodCannotUseItsHttpClient() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var requester = new com.tcl.ff.component.overseahttp.http.HttpRequester(new okhttp3.OkHttpClient());
            assertNull(requester.postJsonSync(server.url("/forbidden-post").toString(),"{}"));
            assertEquals(0,server.getRequestCount());
        }
    }
}
