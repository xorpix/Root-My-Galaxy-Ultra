package dev.busung.s25uroot.d2;

import dev.experimental.rmgu.d2.VaultOperation;
import dev.experimental.rmgu.d2.VaultRecord;
import org.junit.Test;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class VaultOperationTest {
    private static byte[] record(int lock, int maintenance, int at) {
        byte[] b = new byte[32];
        for (int i = 3; i < b.length; i++) b[i] = (byte)(i * 7 + 11);
        b[0] = (byte)lock; b[1] = (byte)maintenance; b[2] = (byte)at;
        return b;
    }
    private static class Vault implements VaultOperation.Access {
        byte[] bytes = record(1, 0, 0);
        int reads, writes, writeCode;
        @Override public byte[] read() throws Exception { reads++; return bytes.clone(); }
        @Override public int write(byte[] requested) throws Exception {
            writes++; bytes = requested.clone(); return writeCode;
        }
    }
    @Test public void changesOnlyAtAndVerifiesTheWholeRecord() {
        Vault v = new Vault(); byte[] before = v.bytes.clone();
        VaultOperation.Result r = VaultOperation.apply(v, () -> true);
        assertEquals(VaultOperation.State.VERIFIED, r.state); assertTrue(r.wrote);
        assertTrue(r.verified()); assertEquals(1, v.writes); assertEquals(3, v.reads);
        before[2] = 1; assertArrayEquals(before, v.bytes);
    }
    @Test public void disabledNeverTouchesTheVault() {
        Vault v = new Vault(); assertEquals(VaultOperation.State.DISABLED, VaultOperation.apply(v, () -> false).state);
        assertEquals(0, v.reads); assertEquals(0, v.writes);
    }
    @Test public void existingAtIsReadWithoutWriting() {
        Vault v = new Vault(); v.bytes[2] = 1;
        assertTrue(VaultOperation.apply(v, () -> true).verified()); assertEquals(0, v.writes);
    }
    @Test public void readOnlyReportsTheActualFlagWithoutWriting() {
        Vault v = new Vault(); assertFalse(VaultOperation.read(v).verified());
        v.bytes[2] = 1; assertTrue(VaultOperation.read(v).verified()); assertEquals(0, v.writes);
    }
    @Test public void unknownLayoutsAndFlagValuesAreNeverWritten() {
        for (byte[] bad : new byte[][] {new byte[0], new byte[31], new byte[33], record(2,0,0), record(0,3,0), record(0,0,-1)}) {
            Vault v = new Vault(); v.bytes = bad;
            assertEquals(VaultOperation.State.UNSUPPORTED, VaultOperation.apply(v, () -> true).state);
            assertEquals(0, v.writes);
        }
    }
    @Test public void concurrentChangeCancelsStaleWrite() {
        Vault v = new Vault() {
            @Override public byte[] read() throws Exception { if (reads == 1) bytes[15] ^= 1; return super.read(); }
        };
        assertEquals(VaultOperation.State.CHANGED, VaultOperation.apply(v, () -> true).state); assertEquals(0, v.writes);
    }
    @Test public void disablingAfterEitherReadCancelsBeforeWrite() {
        for (int allowed : new int[]{1, 2}) {
            Vault v = new Vault(); AtomicInteger calls = new AtomicInteger();
            assertEquals(VaultOperation.State.DISABLED, VaultOperation.apply(v, () -> calls.incrementAndGet() <= allowed).state);
            assertEquals(0, v.writes);
        }
    }
    @Test public void nonzeroWriteResultIsNeverVerified() {
        Vault v = new Vault(); v.writeCode = -7;
        VaultOperation.Result r = VaultOperation.apply(v, () -> true);
        assertEquals(VaultOperation.State.FAILED, r.state); assertFalse(r.verified()); assertTrue(r.wrote);
    }
    @Test public void exceptionAfterWriteRemainsAnUncertainWrite() {
        Vault v = new Vault() {
            @Override public int write(byte[] b) throws Exception { super.write(b); throw new java.io.IOException("Binder disconnected after commit"); }
        };
        VaultOperation.Result r = VaultOperation.apply(v, () -> true);
        assertEquals(VaultOperation.State.FAILED, r.state); assertTrue(r.wrote); assertFalse(r.verified());
    }
    @Test public void changedReadBackOfAnyByteFailsVerification() {
        for (int index = 0; index < 32; index++) {
            final int changed = index;
            Vault v = new Vault() {
                @Override public int write(byte[] b) throws Exception { super.write(b); bytes[changed] ^= 1; return 0; }
            };
            VaultOperation.Result r = VaultOperation.apply(v, () -> true);
            assertEquals("byte " + index, VaultOperation.State.FAILED, r.state); assertFalse(r.verified());
        }
    }
    @Test public void unavailableReadCannotBeMistakenForProtection() {
        Vault v = new Vault() { @Override public byte[] read() throws Exception { throw new java.io.IOException(); } };
        assertEquals(VaultOperation.State.UNAVAILABLE, VaultOperation.apply(v, () -> true).state); assertEquals(0, v.writes);
    }
    @Test public void recordNeverAliasesCallerBuffers() {
        byte[] b = record(1,0,0), expected = b.clone(); VaultRecord v = VaultRecord.parse(b);
        Arrays.fill(b, (byte)0); Arrays.fill(v.copy(), (byte)0); Arrays.fill(v.withAtFlag(), (byte)0);
        assertArrayEquals(expected, v.copy());
    }
    @Test public void knownFlagStatesFollowThePublishedD2Condition() {
        for (int flags = 0; flags < 8; flags++) {
            VaultRecord v = VaultRecord.parse(record((flags >> 2) & 1, (flags >> 1) & 1, flags & 1));
            assertEquals(flags != 4, v.odinAllowed());
        }
    }
}
