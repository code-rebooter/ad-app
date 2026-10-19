package com.smart.android.ad_app.google

import org.junit.Assert.*
import org.junit.Test

class GoogleTclFillTest {
    private val inline = """<VAST version="3.0"><Ad><InLine><Creatives><Creative><Linear><MediaFiles><MediaFile><![CDATA[https://media.example.com/ad.mp4]]></MediaFile></MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"""

    @Test fun `only a well formed final inline video response counts as filled`() {
        assertTrue(GoogleTclVastFillDetector.hasInlineVideo(inline))
        assertFalse(GoogleTclVastFillDetector.hasInlineVideo("<VAST/>"))
        assertFalse(GoogleTclVastFillDetector.hasInlineVideo("<VAST><Ad><Wrapper><VASTAdTagURI>https://a/next</VASTAdTagURI></Wrapper></Ad></VAST>"))
        assertFalse(GoogleTclVastFillDetector.hasInlineVideo(inline.dropLast(5)))
        assertFalse(GoogleTclVastFillDetector.hasInlineVideo("<VAST><!-- $inline --></VAST>"))
        assertFalse(GoogleTclVastFillDetector.hasInlineVideo(inline.replace("https://media.example.com/ad.mp4", " ")))
        assertFalse(GoogleTclVastFillDetector.hasInlineVideo(inline.replace("https://media.example.com/ad.mp4", "javascript:test")))
    }

    @Test fun `namespace prefixes and CDATA do not hide a returned inline video`() {
        val prefixed=inline.replace(Regex("""<(/?)([A-Za-z]+)"""), "<$1v:$2")
            .replace("<v:VAST version", "<v:VAST xmlns:v=\"urn:vast\" version")
        assertTrue(GoogleTclVastFillDetector.hasInlineVideo(prefixed))
    }

    @Test fun `wrapper forks report filled once for their original request`() {
        var count=0
        val handle=GoogleTclFillBridge.watch("https://ads.example.com/root?x=one") { count++ }
        try {
            GoogleTclFillBridge.bind("native-one", "https://ads.example.com/root?x=one&requestId=native-one")
            GoogleTclFillBridge.observe("<VAST><Ad><Wrapper/></Ad></VAST>", "native-one")
            assertEquals(0,count)
            GoogleTclFillBridge.bind("native-one", "https://other.example.com/child?requestId=native-one")
            GoogleTclFillBridge.observe(inline, "native-one")
            GoogleTclFillBridge.observe(inline, "native-one")
            assertEquals(1,count)
        } finally { handle.cancel() }
    }

    @Test fun `cancelled and unrelated native responses cannot fill the next host request`() {
        var oldCount=0;var nextCount=0
        val url="https://ads.example.com/root?x=two"
        val old=GoogleTclFillBridge.watch(url) { oldCount++ }
        GoogleTclFillBridge.bind("old-native", url)
        old.cancel()
        val next=GoogleTclFillBridge.watch(url) { nextCount++ }
        try {
            GoogleTclFillBridge.observe(inline,"old-native")
            GoogleTclFillBridge.bind("unrelated", "https://other.example.com/ads")
            GoogleTclFillBridge.observe(inline,"unrelated")
            assertEquals(0,oldCount);assertEquals(0,nextCount)
            GoogleTclFillBridge.bind("new-native",url)
            GoogleTclFillBridge.observe(inline,"new-native")
            assertEquals(1,nextCount)
        } finally { next.cancel() }
    }
}
