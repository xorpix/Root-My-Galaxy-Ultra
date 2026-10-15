package dev.experimental.rmgu.d2helper;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import dev.experimental.rmgu.d2.D2Configuration;
import dev.experimental.rmgu.d2.D2Protocol;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** The system process owns system data; an APK's app-data SELinux label need not allow it writes. */
final class D2SettingsFile implements D2Configuration.Storage {
    private final AtomicFile file = new AtomicFile(new File(D2Protocol.SETTINGS_FILE));

    private static boolean validate(String suffix) throws IOException {
        try {
            StructStat stat = Os.lstat(D2Protocol.SETTINGS_FILE + suffix);
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != 1000 || stat.st_gid != 1000 ||
                    stat.st_nlink != 1 || (stat.st_mode & 0077) != 0 || stat.st_size > 16) {
                throw new IOException("Unexpected D2 settings file ownership, type, permissions or size");
            }
            return true;
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) return false;
            throw new IOException("Cannot inspect D2 system settings: " + error.getMessage(), error);
        }
    }
    @Override public byte[] read() throws IOException {
        boolean base = validate("");
        boolean backup = validate(".bak");
        validate(".new");
        if (!base && !backup) return null; // A .new file alone was never committed.
        try (FileInputStream input = file.openRead()) { return input.readNBytes(9); }
    }
    @Override public void write(byte[] bytes) throws IOException {
        if (bytes.length != 8) throw new IOException("Invalid D2 settings size");
        validate(""); validate(".bak"); validate(".new");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            Os.fchmod(output.getFD(), 0600);
            output.write(bytes);
            output.getFD().sync(); // Surface fsync failures; AtomicFile otherwise only logs them.
            file.finishWrite(output);
            output = null;
            FileDescriptor directory = Os.open("/data/system", OsConstants.O_RDONLY |
                    OsConstants.O_DIRECTORY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            try { Os.fsync(directory); } finally { Os.close(directory); }
        } catch (ErrnoException error) {
            throw new IOException("Cannot save D2 system settings: " + error.getMessage(), error);
        } finally {
            if (output != null) file.failWrite(output);
        }
    }
}
