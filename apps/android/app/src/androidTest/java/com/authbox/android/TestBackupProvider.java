package com.authbox.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

public final class TestBackupProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.authbox.backup+json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {"public-test.authbox-backup", 0});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || !Arrays.asList("input", "output", "oversized", "bad-utf8").contains(name)) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), "backup-" + name);
        if (!"r".equals(mode)) {
            if (!"output".equals(name) || !"wt".equals(mode)) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        try {
            if ("input".equals(name)) {
                try (InputStream input = getContext().getAssets().open("backup.authbox-backup"); FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                }
            } else if ("oversized".equals(name)) {
                try (RandomAccessFile output = new RandomAccessFile(file, "rw")) { output.setLength(16 * 1024 * 1024 + 1); }
            } else if ("bad-utf8".equals(name)) {
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[] {(byte) 255}); }
            }
        } catch (IOException error) { throw new FileNotFoundException("Synthetic backup fixture unavailable"); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
