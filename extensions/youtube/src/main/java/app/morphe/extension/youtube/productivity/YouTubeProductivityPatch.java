/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.productivity;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.PlaybackInFeedsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/** Runtime hooks for the permanent productivity guard. */
@SuppressWarnings("unused")
public final class YouTubeProductivityPatch {
    private static final String PREFERENCES_NAME = "morphe_youtube_productivity";
    private static final String KEY_DAY = "day";
    private static final String KEY_WATCHED_MS = "watched_ms";
    private static final String KEY_EXCLUSIVE_VIDEO = "exclusive_video";
    private static final String KEY_FINISHER_VIDEO = "finisher_video";
    private static final String KEY_LONG_VIDEO_WATCHED = "long_video_watched";

    /** Ignore an abnormally delayed callback, such as after a pause or a process stall. */
    private static final long MAX_TICK_ELAPSED_MS = 2_000;
    private static final long MESSAGE_COOLDOWN_MS = 2_000;

    private static final Object lock = new Object();
    private static final YouTubeViewingPolicy.State state = new YouTubeViewingPolicy.State();

    private static String stateDay = "";
    private static String sessionVideoId = "";
    private static long sessionVideoStartWatchedMs;
    private static long sessionVideoPlaybackMs;
    private static long lastPlaybackTickElapsedMs = -1;
    private static boolean playerIsPlaying;
    private static boolean restrictionsApplied;
    private static boolean dismissRequested;
    private static String lastMessageKey = "";
    private static long lastMessageElapsedMs;

    private YouTubeProductivityPatch() {
    }

    static {
        // VideoInformation emits this only for an actual player video, not for ordinary feed thumbnails.
        VideoInformation.onVideoIdChange.addObserver(YouTubeProductivityPatch::videoIdChanged);
    }

    /** Makes the setting unavailable after it has been intentionally switched on. */
    public static final class ProductivityGuardAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return !Settings.YOUTUBE_PRODUCTIVITY_LOCKED.get();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.YOUTUBE_PRODUCTIVITY_LOCKED);
        }
    }

    /** Called by the video status hook before YouTube changes the player state. */
    public static boolean shouldBlockPlayerStatus(Enum<?> status) {
        try {
            if (!isEnabled() || status == null || !"VIDEO_PLAYING".equals(status.name())) {
                return false;
            }
            synchronized (lock) {
                ensureRestrictions();
                final YouTubeViewingPolicy.Decision decision = startCurrentPlaybackAttempt();
                if (decision == YouTubeViewingPolicy.Decision.ALLOW) {
                    return false;
                }
                showBlockedMessage(decision);
                requestDismissPlayer();
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "YouTube productivity status check failed", ex);
            // A failed guard must not break YouTube's own player.
            return false;
        }
    }

    /** Called after an allowed player status change, so paused time is not counted. */
    public static void playerStatusChanged(Enum<?> status) {
        if (!isEnabled()) {
            return;
        }
        synchronized (lock) {
            playerIsPlaying = status != null && "VIDEO_PLAYING".equals(status.name());
            lastPlaybackTickElapsedMs = -1;
        }
    }

    /** Called about once per second by the real player, including background playback. */
    public static void videoTimeChanged(long ignoredVideoPositionMs) {
        if (!isEnabled()) {
            return;
        }
        synchronized (lock) {
            ensureRestrictions();

            final YouTubeViewingPolicy.Decision decision = startCurrentPlaybackAttempt();
            if (decision != YouTubeViewingPolicy.Decision.ALLOW) {
                playerIsPlaying = false;
                lastPlaybackTickElapsedMs = -1;
                showBlockedMessage(decision);
                requestDismissPlayer();
                return;
            }
            if (!playerIsPlaying) {
                lastPlaybackTickElapsedMs = -1;
                return;
            }

            final String videoId = currentVideoId();
            if (videoId.isEmpty()) {
                lastPlaybackTickElapsedMs = -1;
                return;
            }

            final long now = SystemClock.elapsedRealtime();
            if (lastPlaybackTickElapsedMs >= 0) {
                final long elapsed = Math.min(MAX_TICK_ELAPSED_MS, Math.max(0, now - lastPlaybackTickElapsedMs));
                if (elapsed > 0) {
                    YouTubeViewingPolicy.recordPlayback(state, videoId, elapsed);
                    sessionVideoPlaybackMs += elapsed;
                    saveState();
                }
            }
            lastPlaybackTickElapsedMs = now;
        }
    }

    /** The current player video changed. Used to distinguish a resumed long video from a new one. */
    public static void videoIdChanged(String videoId) {
        if (!isEnabled()) {
            return;
        }
        synchronized (lock) {
            loadStateForToday();
            sessionVideoId = videoId == null ? "" : videoId;
            sessionVideoStartWatchedMs = state.watchedMs;
            sessionVideoPlaybackMs = 0;
            lastPlaybackTickElapsedMs = -1;
        }
    }

    private static boolean isEnabled() {
        if (!Settings.YOUTUBE_PRODUCTIVITY_LOCKED.get()) {
            return false;
        }
        // Keep the visible setting honest if an old settings screen tried to turn it off.
        if (!Settings.YOUTUBE_PRODUCTIVITY_GUARD.get()) {
            Settings.YOUTUBE_PRODUCTIVITY_GUARD.save(true);
        }
        return true;
    }

    private static YouTubeViewingPolicy.Decision startCurrentPlaybackAttempt() {
        loadStateForToday();

        final String videoId = currentVideoId();
        final long videoLength = VideoInformation.getVideoLength();
        final boolean liveStream = VideoInformation.isLiveStream();
        ensureSession(videoId);

        final YouTubeViewingPolicy.Decision decision = YouTubeViewingPolicy.startPlaybackAttempt(
                state,
                LocalDate.now().getDayOfWeek(),
                videoId,
                liveStream,
                videoLength,
                sessionVideoStartWatchedMs,
                sessionVideoPlaybackMs
        );
        if (decision == YouTubeViewingPolicy.Decision.ALLOW) {
            saveState();
        }
        return decision;
    }

    private static String currentVideoId() {
        final String startingVideoId = VideoInformation.getStartingOrPlayingVideoId();
        return startingVideoId == null ? "" : startingVideoId;
    }

    private static void ensureSession(String videoId) {
        if (videoId.equals(sessionVideoId)) {
            return;
        }
        sessionVideoId = videoId;
        sessionVideoStartWatchedMs = state.watchedMs;
        sessionVideoPlaybackMs = 0;
        lastPlaybackTickElapsedMs = -1;
    }

    /** Enforces no autoplay in feeds and hides lives as soon as the permanent guard is active. */
    private static void ensureRestrictions() {
        if (restrictionsApplied) {
            return;
        }
        restrictionsApplied = true;
        Settings.HIDE_LIVE_STREAMS.save(true);
        Settings.PLAYBACK_IN_FEEDS.save(PlaybackInFeedsPatch.MODE_OFF);
        Settings.PLAYBACK_IN_FEEDS_IMPORTED.save(true);
        PlaybackInFeedsPatch.setMode(PlaybackInFeedsPatch.MODE_OFF);
    }

    private static void requestDismissPlayer() {
        if (dismissRequested) {
            return;
        }
        dismissRequested = true;
        Utils.runOnMainThreadDelayed(() -> {
            synchronized (lock) {
                dismissRequested = false;
            }
            try {
                final LoadVideoPatch.PlayerInterface player = LoadVideoPatch.getPlayerInterface();
                if (player != null) {
                    player.patch_dismissPlayer();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Could not close blocked YouTube player", ex);
            }
        }, 50);
    }

    private static void showBlockedMessage(YouTubeViewingPolicy.Decision decision) {
        final String messageKey = switch (decision) {
            case WEEKDAY -> "morphe_youtube_productivity_weekday_blocked";
            case LIVE_STREAM -> "morphe_youtube_productivity_live_blocked";
            case DAILY_FINISHER -> "morphe_youtube_productivity_daily_limit_reached";
            case EXCLUSIVE_LONG_VIDEO -> "morphe_youtube_productivity_exclusive_long_video";
            case LONG_VIDEO_DECISION_CLOSED -> "morphe_youtube_productivity_long_video_decision_closed";
            case ALLOW -> "";
        };
        if (messageKey.isEmpty()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (messageKey.equals(lastMessageKey) && now - lastMessageElapsedMs < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessageKey = messageKey;
        lastMessageElapsedMs = now;
        Utils.showSnackbarLong(str(messageKey));
    }

    private static void loadStateForToday() {
        final SharedPreferences preferences = preferences();
        final String today = LocalDate.now().toString();
        if (today.equals(stateDay)) {
            return;
        }

        state.clear();
        stateDay = today;
        if (preferences == null || !today.equals(preferences.getString(KEY_DAY, ""))) {
            saveState();
            return;
        }

        state.watchedMs = Math.max(0, preferences.getLong(KEY_WATCHED_MS, 0));
        state.exclusiveVideoId = preferences.getString(KEY_EXCLUSIVE_VIDEO, "");
        state.finisherVideoId = preferences.getString(KEY_FINISHER_VIDEO, "");
        final String storedLongVideos = preferences.getString(KEY_LONG_VIDEO_WATCHED, "");
        if (storedLongVideos != null && !storedLongVideos.isEmpty()) {
            for (String item : storedLongVideos.split(";")) {
                final String[] parts = item.split(":", 2);
                if (parts.length != 2 || parts[0].isEmpty()) {
                    continue;
                }
                try {
                    state.longVideoWatchedMs.put(parts[0], Math.max(0, Long.parseLong(parts[1])));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed old state instead of preventing playback.
                }
            }
        }
    }

    private static void saveState() {
        final SharedPreferences preferences = preferences();
        if (preferences == null) {
            return;
        }
        final StringBuilder longVideos = new StringBuilder();
        for (Map.Entry<String, Long> entry : state.longVideoWatchedMs.entrySet()) {
            if (longVideos.length() > 0) {
                longVideos.append(';');
            }
            longVideos.append(entry.getKey()).append(':').append(entry.getValue());
        }
        preferences.edit()
                .putString(KEY_DAY, stateDay)
                .putLong(KEY_WATCHED_MS, state.watchedMs)
                .putString(KEY_EXCLUSIVE_VIDEO, state.exclusiveVideoId)
                .putString(KEY_FINISHER_VIDEO, state.finisherVideoId)
                .putString(KEY_LONG_VIDEO_WATCHED, longVideos.toString())
                .apply();
    }

    private static SharedPreferences preferences() {
        final Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
