/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.productivity

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.hide.general.hideLayoutComponentsPatch
import app.morphe.patches.youtube.layout.playbackinfeeds.playbackInFeedsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.playerStatusHook
import app.morphe.patches.youtube.video.information.playerStatusOverrideHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/productivity/YouTubeProductivityPatch;"

val youtubeProductivityPatch = patch(
    name = "Permanent YouTube productivity guard",
    description = "Permanently blocks weekday playback, live streams and excess weekend viewing time.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
        loadVideoHookPatch,
        playbackInFeedsPatch,
        hideLayoutComponentsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference(
                    key = "morphe_youtube_productivity_guard",
                    summary = true,
                    tag = "app.morphe.extension.youtube.settings.preference.PermanentProductivitySwitchPreference",
                ),
            )
        )

        // Count actual player time, rather than the media timestamp, so seeking and playback speed
        // never alter the daily allowance.
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
        playerStatusHook(EXTENSION_CLASS, "playerStatusChanged")
        playerStatusOverrideHook(EXTENSION_CLASS, "shouldBlockPlayerStatus")
    }
}
