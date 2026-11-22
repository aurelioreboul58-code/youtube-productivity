package app.morphe.extension.youtube.productivity;

import static org.junit.Assert.assertEquals;

import java.time.DayOfWeek;

import org.junit.Test;

public class YouTubeViewingPolicyTest {
    private static final String FIRST = "first_video";
    private static final String SECOND = "second_video";
    private static final long SHORT_VIDEO = 10 * 60 * 1000L;
    private static final long LONG_VIDEO = 31 * 60 * 1000L;

    @Test
    public void weekdaysBlockAllPlayback() {
        YouTubeViewingPolicy.State state = new YouTubeViewingPolicy.State();

        assertEquals(YouTubeViewingPolicy.Decision.WEEKDAY,
                YouTubeViewingPolicy.canStart(state, DayOfWeek.MONDAY, FIRST, false, SHORT_VIDEO, 0));
    }

    @Test
    public void liveStreamsAreAlwaysBlocked() {
        YouTubeViewingPolicy.State state = new YouTubeViewingPolicy.State();

        assertEquals(YouTubeViewingPolicy.Decision.LIVE_STREAM,
                YouTubeViewingPolicy.canStart(state, DayOfWeek.SATURDAY, FIRST, true, SHORT_VIDEO, 0));
    }

    @Test
    public void leavingLongVideoChargesOnlyOneMinimum() {
        YouTubeViewingPolicy.State state = new YouTubeViewingPolicy.State();
        YouTubeViewingPolicy.startPlaybackAttempt(state, DayOfWeek.SATURDAY, FIRST, false, LONG_VIDEO, 0, 0);
        YouTubeViewingPolicy.recordPlayback(state, FIRST, 2 * 60 * 1000L);

        assertEquals(10 * 60 * 1000L, YouTubeViewingPolicy.effectiveWatchedMs(state));

        YouTubeViewingPolicy.startPlaybackAttempt(state, DayOfWeek.SATURDAY, FIRST, false, LONG_VIDEO, 0, 0);
        assertEquals(10 * 60 * 1000L, YouTubeViewingPolicy.effectiveWatchedMs(state));
    }

    @Test
    public void tenMinutesMakesLongVideoExclusive() {
        YouTubeViewingPolicy.State state = new YouTubeViewingPolicy.State();
        YouTubeViewingPolicy.startPlaybackAttempt(state, DayOfWeek.SATURDAY, FIRST, false, LONG_VIDEO, 0, 0);
        YouTubeViewingPolicy.recordPlayback(state, FIRST, 10 * 60 * 1000L);

        assertEquals(YouTubeViewingPolicy.Decision.EXCLUSIVE_LONG_VIDEO,
                YouTubeViewingPolicy.canStart(state, DayOfWeek.SATURDAY, SECOND, false, SHORT_VIDEO, 0));
        assertEquals(YouTubeViewingPolicy.Decision.ALLOW,
                YouTubeViewingPolicy.canStart(state, DayOfWeek.SATURDAY, FIRST, false, LONG_VIDEO, 0));
    }

    @Test
    public void newLongVideoMustBeChosenBeforeFiveMinutes() {
        YouTubeViewingPolicy.State state = new YouTubeViewingPolicy.State();
        YouTubeViewingPolicy.recordPlayback(state, FIRST, 5 * 60 * 1000L + 1);

        assertEquals(YouTubeViewingPolicy.Decision.LONG_VIDEO_DECISION_CLOSED,
                YouTubeViewingPolicy.canStart(state, DayOfWeek.SUNDAY, SECOND, false, LONG_VIDEO, state.watchedMs));
    }

    @Test
    public void dailyLimitLetsTheCurrentVideoFinishOnly() {
        YouTubeViewingPolicy.State state = new YouTubeViewingPolicy.State();
        YouTubeViewingPolicy.recordPlayback(state, FIRST, YouTubeViewingPolicy.DAILY_LIMIT_MS);

        assertEquals(YouTubeViewingPolicy.Decision.ALLOW,
                YouTubeViewingPolicy.canStart(state, DayOfWeek.SUNDAY, FIRST, false, SHORT_VIDEO, 0));
        assertEquals(YouTubeViewingPolicy.Decision.DAILY_FINISHER,
                YouTubeViewingPolicy.canStart(state, DayOfWeek.SUNDAY, SECOND, false, SHORT_VIDEO, 0));
    }
}
