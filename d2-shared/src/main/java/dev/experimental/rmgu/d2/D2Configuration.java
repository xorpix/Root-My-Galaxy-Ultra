package dev.experimental.rmgu.d2;

import java.io.IOException;
import java.util.Arrays;

/** No preference cache: only a completed write with matching read-back acknowledges a change. */
public final class D2Configuration {
    public interface Storage {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }
    public static final class State {
        public final boolean activated, enabled;
        private State(boolean activated, boolean enabled) {
            this.activated = activated; this.enabled = enabled;
        }
    }
    private final Storage storage;
    public D2Configuration(Storage storage) { this.storage = storage; }

    public synchronized State read() throws IOException { return decode(storage.read()); }
    public synchronized void activate() throws IOException {
        State before = read();
        if (!before.activated) save(new State(true, false));
    }
    public synchronized void setEnabled(boolean enabled) throws IOException {
        State before = read();
        if (enabled && !before.activated) throw new IOException("Activate the helper before enabling D2");
        if (enabled != before.enabled) save(new State(before.activated, enabled));
    }
    private void save(State state) throws IOException {
        byte[] requested = encode(state);
        storage.write(requested.clone());
        if (!Arrays.equals(requested, storage.read())) {
            throw new IOException("D2 settings read-back did not match; the flag write was not started");
        }
    }
    private static byte[] encode(State state) {
        return new byte[]{'R', 'M', 'G', 'U', 'D', '2', 1,
                (byte)((state.activated ? 1 : 0) | (state.enabled ? 2 : 0))};
    }
    private static State decode(byte[] bytes) throws IOException {
        if (bytes == null) return new State(false, false);
        if (bytes.length != 8 || bytes[6] != 1 || bytes[7] < 0 || bytes[7] > 3 || bytes[7] == 2 ||
                !Arrays.equals(Arrays.copyOf(bytes, 6), new byte[]{'R', 'M', 'G', 'U', 'D', '2'})) {
            throw new IOException("D2 settings have an unsupported format; no flag write was authorized");
        }
        return new State((bytes[7] & 1) != 0, (bytes[7] & 2) != 0);
    }
}
