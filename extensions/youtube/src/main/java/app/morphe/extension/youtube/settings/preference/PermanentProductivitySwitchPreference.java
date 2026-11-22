/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.BooleanSettingPreference;
import app.morphe.extension.youtube.settings.Settings;

/** A switch that immediately and permanently locks the productivity guard when enabled. */
@SuppressWarnings({"deprecation", "unused"})
public final class PermanentProductivitySwitchPreference extends BooleanSettingPreference {
    public PermanentProductivitySwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public PermanentProductivitySwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public PermanentProductivitySwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public PermanentProductivitySwitchPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        if (value || Settings.YOUTUBE_PRODUCTIVITY_LOCKED.get()) {
            Settings.YOUTUBE_PRODUCTIVITY_LOCKED.save(true);
            final boolean saved = super.persistBoolean(true);
            setChecked(true);
            setEnabled(false);
            return saved;
        }
        return super.persistBoolean(false);
    }
}
