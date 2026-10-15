package dev.experimental.rmgu.d2;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Read/modify/verify transaction independent of Android, exercised with a fake vault. */
public final class VaultOperation {
    private VaultOperation() {}
    public interface Access {
        byte[] read() throws Exception;
        int write(byte[] bytes) throws Exception;
    }
    public enum State { READ, VERIFIED, ALREADY_SET, DISABLED, UNSUPPORTED, UNAVAILABLE, CHANGED, FAILED }
    public static final class Result {
        public final State state;
        public final VaultRecord record;
        public final String detail;
        public final boolean wrote;
        Result(State state, VaultRecord record, String detail, boolean wrote) {
            this.state = state;
            this.record = record;
            this.detail = detail;
            this.wrote = wrote;
        }
        public boolean verified() {
            return state == State.VERIFIED || state == State.ALREADY_SET ||
                    (state == State.READ && record != null && record.at() == 1);
        }
    }
    public static final class Unsupported extends Exception {
        public Unsupported(String message) { super(message); }
    }
    private static Result result(State state, VaultRecord record, String detail, boolean wrote) {
        return new Result(state, record, detail, wrote);
    }
    public static Result read(Access access) {
        try {
            return result(State.READ, VaultRecord.parse(access.read()), "DMC flags read", false);
        } catch (IllegalArgumentException | Unsupported error) {
            return result(State.UNSUPPORTED, null, error.getMessage(), false);
        } catch (Exception error) {
            return result(State.UNAVAILABLE, null, "DMC service is not available", false);
        }
    }
    public static Result apply(Access access, BooleanSupplier permitted) {
        if (!permitted.getAsBoolean()) return result(State.DISABLED, null, "No write requested", false);
        boolean wrote = false;
        try {
            VaultRecord before = VaultRecord.parse(access.read());
            if (!permitted.getAsBoolean()) return result(State.DISABLED, before, "Write cancelled", false);
            if (before.at() == 1) return result(State.ALREADY_SET, before, "AT flag already set", false);
            // Reduce the read/modify/write race. A changed record is never overwritten from an old snapshot.
            byte[] current = access.read();
            VaultRecord.parse(current);
            if (!before.same(current)) return result(State.CHANGED, null, "DMC changed before the write; retry later", false);
            if (!permitted.getAsBoolean()) return result(State.DISABLED, before, "Write cancelled", false);
            byte[] requested = before.withAtFlag();
            wrote = true;
            // A Binder failure can happen after the secure service committed the write.
            int code = access.write(requested.clone());
            if (code != 0) return result(State.FAILED, null, "DMC rejected the write (" + code + ")", true);
            byte[] received = access.read();
            VaultRecord check = VaultRecord.parse(received);
            // Verify all bytes, including the fields this implementation intentionally does not interpret.
            if (!Arrays.equals(requested, received)) {
                return result(State.FAILED, check, "DMC read-back changed; protection is unconfirmed", true);
            }
            return result(State.VERIFIED, check, "Complete DMC read-back verified", true);
        } catch (IllegalArgumentException | Unsupported error) {
            return result(State.UNSUPPORTED, null, error.getMessage(), wrote);
        } catch (Exception error) {
            return result(wrote ? State.FAILED : State.UNAVAILABLE, null,
                    wrote ? "DMC write could not be verified" : "DMC service is not available", wrote);
        }
    }
}
