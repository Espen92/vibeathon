package com.vibeathon.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every UI selector used to automate the ChatGPT app lives here.
 *
 * <p>Nodes are matched by their content description or text (never by coordinates). A node
 * matches a selector when its trimmed label equals one of the candidates, ignoring case.
 * When ChatGPT renames a button, add the new label to the relevant list (see README,
 * "Updating selectors") and use the in-app Debug log to see which labels were found.
 */
public final class Selectors {

    public static final String CHATGPT_PACKAGE = "com.openai.chatgpt";

    /** Composer send button. */
    public static final List<String> SEND = list(
            "Send", "Send message", "Send prompt", "Submit");

    /** Shown while a reply is streaming. */
    public static final List<String> STOP_GENERATING = list(
            "Stop generating", "Stop", "Stop streaming", "Stop response");

    /** Read-aloud action on an assistant message. */
    public static final List<String> READ_ALOUD = list(
            "Read aloud", "Read out loud", "Read out", "Listen");

    /** Shown while read-aloud is playing; used for barge-in and to detect the end of playback. */
    public static final List<String> STOP_READING = list(
            "Stop reading", "Stop read aloud", "Stop speaking", "Stop playback", "Pause");

    /** Buttons that only appear on finished assistant messages (used to detect a new reply). */
    public static final List<String> RESPONSE_ACTIONS = list(
            "Read aloud", "Good response", "Bad response", "Regenerate", "Try again");

    /** Per-message overflow menu. */
    public static final List<String> MORE_ACTIONS = list(
            "More actions", "More", "More options", "Message actions");

    /** Composer attachment ("+") button. */
    public static final List<String> ATTACH = list(
            "Attach", "Add attachment", "Add photos & files", "Add photos and files",
            "Add files", "Attachments", "Open attachment menu", "Add");

    /** Entry in the attachment menu that opens the system file picker. */
    public static final List<String> ATTACH_FILE = list(
            "Files", "File", "Upload file", "Upload files", "Attach file", "Attach files",
            "Browse files");

    /** Floating button that jumps to the newest message. */
    public static final List<String> SCROLL_TO_BOTTOM = list(
            "Scroll to bottom", "Jump to bottom", "Scroll down", "Jump to latest");

    private Selectors() {
    }

    public static boolean matches(List<String> candidates, CharSequence label) {
        if (label == null) {
            return false;
        }
        String normalized = normalize(label);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String candidate : candidates) {
            if (normalize(candidate).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when either the content description or the text matches. */
    public static boolean matchesAny(List<String> candidates, CharSequence contentDescription,
            CharSequence text) {
        return matches(candidates, contentDescription) || matches(candidates, text);
    }

    static String normalize(CharSequence value) {
        return value.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static List<String> list(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
