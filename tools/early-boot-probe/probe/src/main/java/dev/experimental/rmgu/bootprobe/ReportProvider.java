package dev.experimental.rmgu.bootprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Only explicit, temporary URI grants expose a generated plain-text report. */
public final class ReportProvider extends ContentProvider {
    static final String AUTHORITY = "dev.experimental.rmgu.bootprobe.reports";
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/plain"; }

    private File file(Uri uri) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority()) || path.size() != 2
                || !path.get(0).equals("reports") || !path.get(1).matches("report-[a-z0-9-]+\\.txt"))
            throw new FileNotFoundException("Unknown report");
        File dir = new File(getContext().createDeviceProtectedStorageContext().getCacheDir(), "reports");
        File file = new File(dir, path.get(1));
        try {
            if (!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()) || !file.isFile())
                throw new FileNotFoundException("Unknown report");
        } catch (IOException error) { throw new FileNotFoundException("Unknown report"); }
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only report");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = file(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) row.add(column.equals(OpenableColumns.DISPLAY_NAME)
                    ? file.getName() : column.equals(OpenableColumns.SIZE) ? file.length() : null);
            return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
}
