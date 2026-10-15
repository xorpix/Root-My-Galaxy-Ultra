package dev.busung.s25uroot.d2;

import dev.experimental.rmgu.d2.D2Configuration;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class D2ConfigurationTest {
    private static final class Store implements D2Configuration.Storage {
        byte[] bytes;
        int writes;
        boolean failRead, failWrite, ignoreWrite, corruptWrite;
        @Override public byte[] read() throws IOException {
            if (failRead) throw new IOException("read denied");
            return bytes == null ? null : bytes.clone();
        }
        @Override public void write(byte[] next) throws IOException {
            writes++;
            if (failWrite) throw new IOException("write denied");
            if (ignoreWrite) return;
            bytes = next.clone();
            if (corruptWrite) bytes[0] ^= 1;
        }
    }
    @Test public void absentSettingsAreOffAndUnactivated() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        assertFalse(config.read().enabled); assertFalse(config.read().activated); assertEquals(0, store.writes);
    }
    @Test public void enablingRequiresSavedActivation() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        assertThrows(IOException.class, () -> config.setEnabled(true));
        assertEquals(0, store.writes); assertFalse(config.read().enabled);
    }
    @Test public void activationPersistsWithoutEnabling() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        config.activate(); D2Configuration restarted = new D2Configuration(store);
        assertTrue(restarted.read().activated); assertFalse(restarted.read().enabled);
    }
    @Test public void enabledSurvivesANewReaderAndRepeatedActivation() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        config.activate(); config.setEnabled(true);
        D2Configuration restarted = new D2Configuration(store); restarted.activate();
        assertTrue(restarted.read().enabled); assertEquals(2, store.writes);
    }
    @Test public void disablingKeepsActivationAndSurvivesRestart() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        config.activate(); config.setEnabled(true); config.setEnabled(false);
        D2Configuration.State state = new D2Configuration(store).read();
        assertTrue(state.activated); assertFalse(state.enabled);
    }
    @Test public void failedActivationDoesNotLeaveAnInMemorySuccess() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store); store.failWrite = true;
        assertThrows(IOException.class, config::activate);
        assertFalse(config.read().activated); assertFalse(config.read().enabled);
    }
    @Test public void failedEnableDoesNotLeaveAnInMemorySuccess() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        config.activate(); store.failWrite = true;
        assertThrows(IOException.class, () -> config.setEnabled(true));
        assertTrue(config.read().activated); assertFalse(config.read().enabled);
    }
    @Test public void failedDisableStillReportsTheActualSavedEnabledValue() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        config.activate(); config.setEnabled(true); store.failWrite = true;
        assertThrows(IOException.class, () -> config.setEnabled(false)); assertTrue(config.read().enabled);
    }
    @Test public void acknowledgedButMissingWriteIsRejected() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        config.activate(); store.ignoreWrite = true;
        assertThrows(IOException.class, () -> config.setEnabled(true)); assertFalse(config.read().enabled);
    }
    @Test public void everySavedByteMustMatchTheRequest() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store);
        config.activate(); store.corruptWrite = true;
        assertThrows(IOException.class, () -> config.setEnabled(true)); assertThrows(IOException.class, config::read);
    }
    @Test public void readsAreAuthoritativeAndReadFailuresAreNotDefaults() throws Exception {
        Store store = new Store(); D2Configuration first = new D2Configuration(store), second = new D2Configuration(store);
        first.activate(); first.setEnabled(true); assertTrue(second.read().enabled);
        second.setEnabled(false); assertFalse(first.read().enabled);
        store.failRead = true; assertThrows(IOException.class, first::read);
        assertThrows(IOException.class, () -> first.setEnabled(true)); assertEquals(3, store.writes);
    }
    @Test public void unknownFormatsAndEnabledWithoutActivationAreRejected() throws Exception {
        Store store = new Store(); D2Configuration config = new D2Configuration(store); config.activate();
        byte[] valid = store.bytes.clone();
        for (int i = 0; i < 8; i++) {
            store.bytes = valid.clone(); store.bytes[i] = (byte)127;
            assertThrows(IOException.class, config::read);
        }
        store.bytes = valid.clone(); store.bytes[7] = 2; assertThrows(IOException.class, config::read);
        store.bytes = new byte[0]; assertThrows(IOException.class, config::read);
        store.bytes = new byte[9]; assertThrows(IOException.class, config::read);
    }
}
