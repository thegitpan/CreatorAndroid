package it.panebarco.creator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Test APK only; Java keeps this separate provider process independent of target Kotlin runtime. */
public final class TestArchiveProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        return new File(getContext().getFilesDir(), "video".equals(uri.getLastPathSegment()) ? "picker-video.mp4" : "round-trip.creator.zip");
    }
    @Override public String getType(Uri uri) { return "video".equals(uri.getLastPathSegment()) ? "video/mp4" : "application/zip"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{file(uri).getName(), file(uri).length()});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
