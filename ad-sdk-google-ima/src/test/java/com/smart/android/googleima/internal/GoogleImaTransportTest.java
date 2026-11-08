package com.smart.android.googleima.internal;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Test;
import static org.junit.Assert.*;
public final class GoogleImaTransportTest {
    private static class Connection extends HttpURLConnection {
        int disconnects;
        Connection() throws Exception { super(new URL("https://fixture.test/vast")); }
        @Override public void disconnect() { disconnects++; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
    }
    @Test public void cancelClosesOnlyItsOwnConnectionsAndIsIdempotent() throws Exception {
        GoogleImaTransport old = new GoogleImaTransport(), next = new GoogleImaTransport();
        Connection a = new Connection(), b = new Connection();
        old.add(a); next.add(b); old.close(); old.close();
        assertEquals(1, a.disconnects); assertEquals(0, b.disconnects);
        next.close(); assertEquals(1, b.disconnects);
    }
    @Test public void openingAfterCancelClosesImmediatelyAndCannotProceed() throws Exception {
        GoogleImaTransport transport = new GoogleImaTransport(); transport.close();
        Connection connection = new Connection();
        try { transport.add(connection); fail("Late request must fail"); }
        catch (IOException expected) { assertEquals(1, connection.disconnects); }
    }
    @Test public void completedRequestsCanBeRemovedWithoutBeingRetainedUntilRelease() throws Exception {
        GoogleImaTransport transport = new GoogleImaTransport(); Connection a = new Connection();
        transport.add(a); transport.remove(a); transport.close(); assertEquals(0, a.disconnects);
    }
    @Test public void oneBrokenConnectionCannotPreventTheRemainingCancellation() throws Exception {
        GoogleImaTransport transport = new GoogleImaTransport();
        Connection bad = new Connection() { @Override public void disconnect() { throw new IllegalStateException(); } };
        Connection good = new Connection(); transport.add(bad); transport.add(good);
        transport.close(); assertEquals(1, good.disconnects);
    }
}
