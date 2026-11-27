// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands a report made by ProblemReport to the app the person shares it with, read only and only by the grant of that
 * share. androidx's FileProvider does the same, but the APK has no dependencies.
 */
public final class ReportProvider extends ContentProvider {
    static Uri uri(Context context, File report) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".reports")
                .appendPath(report.getName()).build();
    }

    static File dir(Context context) {
        File d = new File(context.getCacheDir(), "report");
        d.mkdirs();
        return d;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Only a report's own name, in the reports' directory: nothing else of the app can be asked for. */
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches(ProblemReport.NAME_PATTERN)) return null;
        File f = new File(dir(getContext()), name);
        return f.isFile() ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        File f = file(uri);
        if (f == null) throw new FileNotFoundException(String.valueOf(uri.getLastPathSegment()));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The name and size apps show (and save the file under). */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/zip";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read only");
    }
}
