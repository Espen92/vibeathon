package com.vibeathon;

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
import java.io.IOException;
import java.util.List;

/**
 * Minimal read-only replacement for AndroidX FileProvider. Serves recordings from
 * {@code cache/recordings/} to apps that were granted a temporary read permission.
 */
public final class AudioFileProvider extends ContentProvider {

    public static final String RECORDINGS_DIR = "recordings";
    public static final String MIME_TYPE = "audio/mp4";

    public static String authority(Context context) {
        return context.getPackageName() + ".files";
    }

    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(RECORDINGS_DIR)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        List<String> segments = uri.getPathSegments();
        if (context == null || segments.size() != 2 || !RECORDINGS_DIR.equals(segments.get(0))) {
            throw new FileNotFoundException("Unknown uri " + uri);
        }
        try {
            File dir = AudioRecorder.recordingsDir(context).getCanonicalFile();
            File file = new File(dir, segments.get(1)).getCanonicalFile();
            if (!dir.equals(file.getParentFile()) || !file.isFile()) {
                throw new FileNotFoundException("Not found " + uri);
            }
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new SecurityException("Read-only provider");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
