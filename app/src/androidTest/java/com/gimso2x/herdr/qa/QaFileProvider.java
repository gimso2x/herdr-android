package com.gimso2x.herdr.qa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Serves the C1 fixture PNG to the app's WebView file chooser. Declared in the androidTest
 * manifest, i.e. it runs in the test package's own process where only framework classes and the
 * test dex exist (no androidx, no Kotlin runtime), hence plain Java on android.content.ContentProvider.
 * It writes the file into its own cache dir on start-up, because the instrumentation code runs as
 * the app and cannot write into the test package's data dir.
 */
public class QaFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.gimso2x.herdr.test.fileprovider";
    public static final String NAME = "qa.png";
    /** A real 1x1 PNG. */
    public static final String PNG_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private File file;

    @Override
    public boolean onCreate() {
        File dir = new File(getContext().getCacheDir(), "qa");
        dir.mkdirs();
        file = new File(dir, NAME);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(Base64.decode(PNG_B64, Base64.DEFAULT));
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {NAME, file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
