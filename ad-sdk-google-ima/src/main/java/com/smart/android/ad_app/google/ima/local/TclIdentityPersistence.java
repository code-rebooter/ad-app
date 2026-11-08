package com.smart.android.ad_app.google.ima.local;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Effective TCL UID XML is authoritative; participating callers share the same OS lock. */
final class TclIdentityPersistence {
    private static final String SETTINGS = "google_tcl_ad";
    private static final String AID = "aid";

    private TclIdentityPersistence() {}

    static String readOrCreate(Context supplied) {
        if (supplied == null) throw new IllegalArgumentException("context == null");
        Context application = supplied.getApplicationContext();
        Context context = application == null ? supplied : application;
        File lockFile = new File(context.getFilesDir(), "tcl_aid.lock");
        try {
            synchronized (("com.smart.android.tclad.identity:" + lockFile.getCanonicalPath()).intern()) {
                try (RandomAccessFile handle = new RandomAccessFile(lockFile, "rw");
                     FileChannel channel = handle.getChannel();
                     FileLock ignored = channel.lock()) {
                    SharedPreferences prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
                    // Wait for this process's framework loader (including .bak recovery).
                    // Its cached value is deliberately NEVER authoritative.
                    try { prefs.getString(AID, ""); } catch (ClassCastException staleType) { /* Read disk below. */ }
                    File xml = preferencesFile(context);
                    String stored = readDisk(xml);
                    if (usable(stored)) return stored;
                    String generated = UUID.randomUUID().toString();
                    if (!commitWithoutCallerCancellation(prefs, generated, false)) {
                        IllegalStateException failure = new IllegalStateException("Cannot persist TCL advertising identity");
                        cleanupUnpersistedMemory(prefs, xml, failure);
                        throw failure;
                    }
                    // commit(true) alone is insufficient when a stale framework map or a
                    // failed backup cleanup leaves a different authoritative disk state.
                    if (!generated.equals(readDisk(xml))) {
                        throw new IllegalStateException("Cannot confirm persisted TCL advertising identity");
                    }
                    return generated;
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot access TCL advertising identity storage", failure);
        }
    }

    private static File preferencesFile(Context context) throws IOException {
        File data = context.getDataDir();
        if (data == null) throw new IOException("Context dataDir unavailable");
        return new File(new File(data, "shared_prefs"), SETTINGS + ".xml").getCanonicalFile();
    }

    private static String readDisk(File xml) throws IOException {
        File parent = xml.getParentFile();
        if (parent == null || (parent.exists() && (!parent.isDirectory() || !parent.canRead()))) {
            throw new IOException("Unreadable TCL preferences directory");
        }
        File backup = new File(xml.getPath() + ".bak");
        File actual = backup.exists() ? backup : xml;
        if (!actual.exists()) return "";
        if (!actual.isFile() || !actual.canRead()) throw new IOException("Unreadable TCL preferences file");
        try (FileInputStream input = new FileInputStream(actual)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity not allowed"); });
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException failure) throws SAXException { throw failure; }
                @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
            });
            Document document = builder.parse(input);
            Element root = document.getDocumentElement();
            if (document.getDoctype() != null || root == null || !"map".equals(root.getTagName())) {
                throw new IOException("Invalid TCL preferences document");
            }
            String result = "";
            boolean found = false;
            NodeList children = root.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node node = children.item(index);
                if (!(node instanceof Element)) continue;
                Element element = (Element) node;
                if (!AID.equals(element.getAttribute("name"))) continue;
                if (found) throw new IOException("Duplicate TCL identity key");
                found = true;
                if ("null".equals(element.getTagName())) continue;
                if (!"string".equals(element.getTagName())) throw new IOException("TCL identity is not a string");
                NodeList contents = element.getChildNodes();
                for (int child = 0; child < contents.getLength(); child++) {
                    if (contents.item(child).getNodeType() == Node.ELEMENT_NODE) {
                        throw new IOException("Invalid nested TCL identity value");
                    }
                }
                result = element.getTextContent();
            }
            return result;
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("Cannot parse persisted TCL advertising identity", failure);
        }
    }

    private static boolean usable(String value) { return value != null && !value.trim().isEmpty(); }

    private static void cleanupUnpersistedMemory(SharedPreferences prefs, File xml, IllegalStateException failure) {
        try {
            if (usable(readDisk(xml))) return;
            if (!commitWithoutCallerCancellation(prefs, null, true)) {
                failure.addSuppressed(new IllegalStateException("Cannot clear unconfirmed TCL advertising identity"));
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static boolean commitWithoutCallerCancellation(SharedPreferences prefs, String value, boolean remove) {
        final boolean[] result = new boolean[1];
        final Throwable[] failure = new Throwable[1];
        Thread writer = new Thread(() -> {
            try {
                SharedPreferences.Editor editor = prefs.edit();
                if (remove) editor.remove(AID); else editor.putString(AID, value);
                result[0] = editor.commit();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        }, "tcl-aid-persist");
        writer.start();
        boolean interrupted = false;
        for (;;) {
            try { writer.join(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
        if (failure[0] != null) throw new IllegalStateException("TCL identity writer failed", failure[0]);
        return result[0];
    }
}
