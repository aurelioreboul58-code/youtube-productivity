/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.productivity;

import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The device-independent rules of the permanent YouTube viewing limit.
 * <p>
 * Time in this class is wall-clock playback time, never the position in the video.
 */
public final class YouTubeViewingPolicy {
    public static final long DAILY_LIMIT_MS = 30 * 60 * 1000L;
    public static final long LONG_VIDEO_MS = 30 * 60 * 1000L;
    public static final long LONG_VIDEO_DECISION_MS = 10 * 60 * 1000L;
    public static final long LONG_VIDEO_MINIMUM_CHARGE_MS = 10 * 60 * 1000L;
    public static final long LONG_VIDEO_START_CUTOFF_MS = 5 * 60 * 1000L;

    public enum Decision {
        ALLOW,
        WEEKDAY,
        LIVE_STREAM,
        DAILY_FINISHER,
        EXCLUSIVE_LONG_VIDEO,
        LONG_VIDEO_DECISION_CLOSED
    }

    /** Mutable state persisted by the Android-facing runtime. */
    public static final class State {
        public long watchedMs;
        public String exclusiveVideoId = "";
        public String finisherVideoId = "";
        /** Actual watched time per long video, in insertion order for stable persistence. */
        public final Map<String, Long> longVideoWatchedMs = new LinkedHashMap<>();

        public void clear() {
            watchedMs = 0;
            exclusiveVideoId = "";
            finisherVideoId = "";
            longVideoWatchedMs.clear();
        }
    }

    private YouTubeViewingPolicy() {
    }

    public static boolean isWeekend(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public static boolean isLongVideo(State state, String videoId, long videoLengthMs) {
        return videoLengthMs > LONG_VIDEO_MS || state.longVideoWatchedMs.containsKey(videoId);
    }

    /**
     * Returns whether a video is allowed to start or resume. A known long video is registered
     * only by {@link #startPlaybackAttempt}, so a thumbnail or an unloaded player never charges time.
     */
    public static Decision canStart(
            State state,
            DayOfWeek day,
            String videoId,
            boolean liveStream,
            long videoLengthMs,
            long watchedMsWhenOpened
    ) {
        if (!isWeekend(day)) {
            return Decision.WEEKDAY;
        }
        if (liveStream) {
            return Decision.LIVE_STREAM;
        }
        if (videoId == null || videoId.isEmpty()) {
            return Decision.ALLOW;
        }
        if (!state.finisherVideoId.isEmpty() && !state.finisherVideoId.equals(videoId)) {
            return Decision.DAILY_FINISHER;
        }
        if (!state.exclusiveVideoId.isEmpty() && !state.exclusiveVideoId.equals(videoId)) {
            return Decision.EXCLUSIVE_LONG_VIDEO;
        }

        final boolean previouslyStartedLongVideo = state.longVideoWatchedMs.containsKey(videoId);
        if (videoLengthMs > LONG_VIDEO_MS
                && !previouslyStartedLongVideo
                && watchedMsWhenOpened > LONG_VIDEO_START_CUTOFF_MS) {
            return Decision.LONG_VIDEO_DECISION_CLOSED;
        }
        return Decision.ALLOW;
    }

    /**
     * Registers a genuine playback start. A long video immediately reserves its first ten minutes
     * exactly once, even if it is closed sooner.
     */
    public static Decision startPlaybackAttempt(
            State state,
            DayOfWeek day,
            String videoId,
            boolean liveStream,
            long videoLengthMs,
            long watchedMsWhenOpened,
            long alreadyWatchedInThisOpenMs
    ) {
        final Decision decision = canStart(
                state, day, videoId, liveStream, videoLengthMs, watchedMsWhenOpened);
        if (decision != Decision.ALLOW || videoId == null || videoId.isEmpty()) {
            return decision;
        }

        if (videoLengthMs > LONG_VIDEO_MS && !state.longVideoWatchedMs.containsKey(videoId)) {
            state.longVideoWatchedMs.put(videoId, Math.max(0, alreadyWatchedInThisOpenMs));
            reserveCurrentVideoIfLimitReached(state, videoId);
        }
        return Decision.ALLOW;
    }

    /** Records actual elapsed playback time after a video was allowed to play. */
    public static void recordPlayback(State state, String videoId, long elapsedMs) {
        if (videoId == null || videoId.isEmpty() || elapsedMs <= 0) {
            return;
        }

        state.watchedMs += elapsedMs;
        if (state.longVideoWatchedMs.containsKey(videoId)) {
            final long longVideoWatchedMs = state.longVideoWatchedMs.get(videoId) + elapsedMs;
            state.longVideoWatchedMs.put(videoId, longVideoWatchedMs);
            if (longVideoWatchedMs >= LONG_VIDEO_DECISION_MS) {
                state.exclusiveVideoId = videoId;
            }
        }
        reserveCurrentVideoIfLimitReached(state, videoId);
    }

    /**
     * Effective use is real playback time plus the unused portion of each attempted long video.
     * This is why closing a long video after two minutes still spends ten minutes, but reopening it
     * never charges that minimum a second time.
     */
    public static long effectiveWatchedMs(State state) {
        long effective = state.watchedMs;
        for (long watched : state.longVideoWatchedMs.values()) {
            effective += Math.max(0, LONG_VIDEO_MINIMUM_CHARGE_MS - watched);
        }
        return effective;
    }

    private static void reserveCurrentVideoIfLimitReached(State state, String videoId) {
        if (state.finisherVideoId.isEmpty() && effectiveWatchedMs(state) >= DAILY_LIMIT_MS) {
            state.finisherVideoId = videoId;
        }
    }
}
