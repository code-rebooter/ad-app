package com.smart.android.ad_app.google;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** Observe an actual response before TCL parsing; do not select or play creatives. */
public final class GoogleTclVastFillDetector {
    private static final List<String> MEDIA = Arrays.asList(
        "VAST", "Ad", "InLine", "Creatives", "Creative", "Linear", "MediaFiles", "MediaFile");
    private GoogleTclVastFillDetector() {}

    public static boolean hasInlineVideo(String xml) {
        if (xml == null || xml.trim().isEmpty()) return false;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            var reader = factory.newSAXParser().getXMLReader();
            for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities",
                    "http://xml.org/sax/features/external-parameter-entities"}) {
                try { reader.setFeature(feature, false); } catch (Exception ignored) { }
            }
            try { reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignored) { }
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Handler handler = new Handler();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return handler.filled;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class Handler extends DefaultHandler {
        final List<String> path = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        boolean filled;
        @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
            path.add(local.isEmpty() ? qName : local);
            if (path.equals(MEDIA)) text.setLength(0);
        }
        @Override public void characters(char[] chars, int start, int length) {
            if (path.equals(MEDIA)) text.append(chars, start, length);
        }
        @Override public void endElement(String uri, String local, String qName) {
            if (path.equals(MEDIA)) {
                try {
                    URI url = new URI(text.toString().trim());
                    if (("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                            && url.getHost() != null && !url.getHost().isEmpty()) filled = true;
                } catch (Exception ignored) { }
            }
            path.remove(path.size() - 1);
        }
    }
}
