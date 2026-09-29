package com.vibeathon;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import com.vibeathon.core.RecordingLimits;

import java.io.File;
import java.io.IOException;

/** Records AAC audio into an .m4a file in the app cache directory. */
public final class AudioRecorder {

    public interface MaxDurationListener {
        void onMaxDurationReached();
    }

    /** Result of {@link #stop()}. {@code file} is null when the recording was discarded. */
    public static final class Result {
        public final File file;
        public final long durationMillis;
        public final boolean tooShort;

        Result(File file, long durationMillis, boolean tooShort) {
            this.file = file;
            this.durationMillis = durationMillis;
            this.tooShort = tooShort;
        }
    }

    private final Context context;
    private MediaRecorder recorder;
    private File outputFile;
    private long startedAt;

    public AudioRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    public static File recordingsDir(Context context) {
        return new File(context.getCacheDir(), AudioFileProvider.RECORDINGS_DIR);
    }

    public boolean isRecording() {
        return recorder != null;
    }

    public void start(int maxSeconds, MaxDurationListener listener) throws IOException {
        if (recorder != null) {
            throw new IllegalStateException("Already recording");
        }
        File dir = recordingsDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        deleteOldRecordings(dir);
        outputFile = new File(dir, "voice-memo-" + System.currentTimeMillis() + ".m4a");

        MediaRecorder r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(context) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(44100);
            r.setAudioChannels(1);
            r.setAudioEncodingBitRate(64000);
            r.setMaxDuration(maxSeconds * 1000);
            r.setOutputFile(outputFile.getAbsolutePath());
            r.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        && listener != null) {
                    listener.onMaxDurationReached();
                }
            });
            r.prepare();
            r.start();
        } catch (IOException | RuntimeException e) {
            r.release();
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
            outputFile = null;
            throw e;
        }
        recorder = r;
        startedAt = SystemClock.elapsedRealtime();
        DebugLog.log("Recording started: " + outputFile.getName() + " (max " + maxSeconds + " s)");
    }

    public Result stop() {
        if (recorder == null) {
            return new Result(null, 0, true);
        }
        long duration = SystemClock.elapsedRealtime() - startedAt;
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // Thrown when no valid audio was captured (e.g. stopped immediately).
            DebugLog.log("MediaRecorder.stop failed (no audio captured): " + e.getMessage());
            ok = false;
        } finally {
            recorder.release();
            recorder = null;
        }
        File file = outputFile;
        outputFile = null;
        if (!ok || RecordingLimits.isTooShort(duration) || file == null || file.length() == 0) {
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            DebugLog.log("Recording discarded (" + duration + " ms)");
            return new Result(null, duration, true);
        }
        DebugLog.log("Recording stopped: " + file.getName() + ", " + duration + " ms, "
                + file.length() + " bytes");
        return new Result(file, duration, false);
    }

    /** Stops and deletes the current recording without returning it. */
    public void cancel() {
        Result result = stop();
        if (result.file != null) {
            //noinspection ResultOfMethodCallIgnored
            result.file.delete();
        }
    }

    private static void deleteOldRecordings(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
