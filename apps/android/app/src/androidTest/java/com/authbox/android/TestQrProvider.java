package com.authbox.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

public final class TestQrProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {"public-test-qr.png", 0});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"r".equals(mode) || uri.getPathSegments().size() != 1 || !Arrays.asList("standard", "page0", "page1", "blank", "oversized", "camera").contains(name)) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (name.equals("oversized")) {
                byte[] bytes = new byte[1024 * 1024];
                for (int index = 0; index < 9; index++) output.write(bytes);
            } else if (name.equals("blank")) {
                Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                bitmap.recycle();
            } else try (InputStream input = getContext().getAssets().open("qr/" + name + ".png")) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
        } catch (IOException error) { throw new FileNotFoundException("Synthetic image fixture unavailable"); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
