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
    @Test public void tclAddressBoundaryCannotStartAnHttpRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var requester = new com.tcl.ff.component.overseahttp.http.HttpRequester(new okhttp3.OkHttpClient());
            assertNull(requester.resolveSuppliedAdAddress(server.url("/business-address").toString(),"{}"));
            assertEquals(0,server.getRequestCount());
        }
    }
    @Test public void originalGenericPostStillSendsTheBodyAndReturnsTheResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("generic-post-ok"));
            var requester = new com.tcl.ff.component.overseahttp.http.HttpRequester(new okhttp3.OkHttpClient());
            assertEquals("generic-post-ok", requester.postJsonSync(server.url("/generic-post").toString(),"{\"probe\":true}"));
            var request = server.takeRequest();
            assertEquals("POST", request.getMethod());
            assertEquals("{\"probe\":true}", request.getBody().readUtf8());
        }
    }
    @Test public void originalFileUtilityCanWriteAndReadItsOwnFixture() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("tcl-file-test");
        java.io.File target = directory.resolve("fixture.txt").toFile();
        try {
            com.tcl.ff.component.overseabase.base.util.FileUtils.writeToFile(target, "file-round-trip");
            assertEquals("file-round-trip", new String(java.nio.file.Files.readAllBytes(target.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } finally { java.nio.file.Files.deleteIfExists(target.toPath()); java.nio.file.Files.deleteIfExists(directory); }
    }
    @Test public void originalProxyDecisionFollowsPlayerConfiguration() throws Exception {
        var config = new com.tcl.uniplayer.tuniplayer.b();
        var enabled = config.getClass().getDeclaredField("h"); enabled.setAccessible(true);
        var restrictions = com.tcl.uniplayer.tuniplayer.c.d.a.a();
        var prioritize = restrictions.getClass().getDeclaredField("h"); prioritize.setAccessible(true);
        var prohibit = restrictions.getClass().getDeclaredField("i"); prohibit.setAccessible(true);
        boolean oldPrioritize=prioritize.getBoolean(restrictions), oldProhibit=prohibit.getBoolean(restrictions);
        try {
            prioritize.setBoolean(restrictions,false); prohibit.setBoolean(restrictions,false);
            enabled.setBoolean(config,true); assertTrue(config.b(true)); assertTrue(config.b(false));
            enabled.setBoolean(config,false); assertFalse(config.b(true));
            prohibit.setBoolean(restrictions,true); enabled.setBoolean(config,true); assertFalse(config.b(true));
        } finally { prioritize.setBoolean(restrictions,oldPrioritize); prohibit.setBoolean(restrictions,oldProhibit); }
    }
    @Test public void uniPlayerVendorTrackerCannotRegisterOrFormatReports() throws Exception {
        var tracker = new com.tcl.tuniplayer_base.d();
        tracker.a(true);
        var implementation = tracker.getClass().getDeclaredField("c");
        implementation.setAccessible(true);
        assertNull(implementation.get(tracker));
        Object mustNotFormat = new Object() {
            @Override public String toString() { throw new AssertionError("Vendor telemetry formatted an event"); }
        };
        tracker.a("event", "value", mustNotFormat, mustNotFormat);
        tracker.a("event", "value", "unused", (java.util.Map<String, String>) null);
    }

    @Test(timeout=4000) public void vastXmlAndBeaconGetRequestsRemainAvailable() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("<VAST version=\"3.0\"/>"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("beacon-ok"));
            var requester = new com.tcl.ff.component.overseahttp.http.HttpRequester(new okhttp3.OkHttpClient());
            assertEquals("<VAST version=\"3.0\"/>", requester.getXmlSync(server.url("/vast").toString()));
            assertEquals("beacon-ok", requester.getJsonSync(server.url("/beacon").toString()));
            assertEquals(2, server.getRequestCount());
            assertEquals("GET", server.takeRequest().getMethod());
            assertEquals("GET", server.takeRequest().getMethod());
        }
    }
}
