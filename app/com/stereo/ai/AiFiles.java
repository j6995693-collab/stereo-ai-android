package com.stereo.ai;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

public class AiFiles extends ContentProvider {
    public static final String AUTHORITY = "com.stereo.ai.files";

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "open");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File resolve(Uri u) throws FileNotFoundException {
        String p = u.getLastPathSegment();
        if (p == null || p.length() == 0 || p.indexOf('/') >= 0) {
            throw new FileNotFoundException("bad name: " + u);
        }
        return new File(dir(getContext()), p);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (!f.isFile()) throw new FileNotFoundException("not a file: " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        int i = n == null ? -1 : n.lastIndexOf('.');
        if (i >= 0) {
            String t = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(n.substring(i + 1).toLowerCase());
            if (t != null) return t;
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] selArgs, String sort) {
        File f;
        try {
            f = resolve(uri);
        } catch (Exception e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder r = c.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) r.add(f.getName());
            else if (OpenableColumns.SIZE.equals(col)) r.add(f.length());
            else r.add(null);
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
