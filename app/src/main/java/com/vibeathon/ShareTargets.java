package com.vibeathon;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.vibeathon.core.Selectors;
import com.vibeathon.core.ShareProbe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Asks the package manager which MIME types ChatGPT (or any app) accepts for {@code ACTION_SEND}.
 *
 * <p>ChatGPT only declares share intent filters for a subset of audio MIME types, so sharing
 * {@code audio/mp4} directly can fail with {@code ActivityNotFoundException}. Probing first
 * lets the app pick a type that actually resolves.
 */
public final class ShareTargets {

    private ShareTargets() {
    }

    /** Resolver limited to one package, or to every app when {@code packageName} is null. */
    public static ShareProbe.Resolver resolver(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        return mimeType -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            if (packageName != null) {
                intent.setPackage(packageName);
            }
            try {
                List<ResolveInfo> infos =
                        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                List<String> components = new ArrayList<>();
                for (ResolveInfo info : infos) {
                    if (info.activityInfo != null) {
                        components.add(info.activityInfo.packageName + "/"
                                + info.activityInfo.name);
                    }
                }
                return components;
            } catch (RuntimeException e) {
                DebugLog.error("queryIntentActivities failed for " + mimeType, e);
                return Collections.emptyList();
            }
        };
    }

    /**
     * Probes ChatGPT's share targets and writes every MIME type and resolved component to the
     * debug log.
     *
     * @return the first MIME type ChatGPT accepts, or null when it accepts none
     */
    public static String probeChatGpt(Context context) {
        return probe(context, Selectors.CHATGPT_PACKAGE, "ChatGPT");
    }

    /**
     * Probes ChatGPT first and, when nothing resolves, all installed share targets. Runs off
     * the caller's thread because a full probe is up to ten package manager queries.
     */
    public static void probeAndLogAllAsync(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> probeAndLogAll(app), "share-target-probe").start();
    }

    /** Probes ChatGPT first and, when nothing resolves, all installed share targets. */
    public static void probeAndLogAll(Context context) {
        DebugLog.log("Probing share targets (" + ShareProbe.MIME_TYPES + ")");
        String supported = probeChatGpt(context);
        if (supported == null) {
            DebugLog.log("ChatGPT accepts no audio MIME type - checking all apps");
            probe(context, null, "All apps");
        } else {
            DebugLog.log("ChatGPT accepts " + supported + " for audio shares");
        }
    }

    private static String probe(Context context, String packageName, String label) {
        List<ShareProbe.Result> results =
                ShareProbe.probe(resolver(context, packageName));
        for (ShareProbe.Result result : results) {
            DebugLog.log("Probe " + label + ": " + result);
        }
        return ShareProbe.firstSupportedMimeType(results);
    }
}
