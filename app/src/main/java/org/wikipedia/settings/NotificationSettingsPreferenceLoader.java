package org.wikipedia.settings;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.graphics.drawable.VectorDrawableCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;

import info.guardianproject.apt.wikipedia.R;

class NotificationSettingsPreferenceLoader extends BasePreferenceLoader {

    NotificationSettingsPreferenceLoader(@NonNull PreferenceFragmentCompat fragment) {
        super(fragment);
    }

    @Override
    public void loadPreferences() {
        loadPreferences(R.xml.preferences_notifications);

        Preference pref = findPreference(R.string.preference_key_notification_poll_enable);
        pref.setOnPreferenceChangeListener(new PollPreferenceListener());

        pref = findPreference(R.string.preference_key_notification_welcome_enable);
        Drawable drawable = VectorDrawableCompat.create(getActivity().getResources(), R.drawable.ic_wikipedia_w, null);
        DrawableCompat.setTint(drawable, Color.BLACK);
        pref.setIcon(drawable);

        pref = findPreference(R.string.preference_key_notification_milestone_enable);
        drawable = VectorDrawableCompat.create(getActivity().getResources(), R.drawable.ic_mode_edit_white_24dp, null);
        DrawableCompat.setTint(drawable, ContextCompat.getColor(getActivity(), R.color.accent50));
        pref.setIcon(drawable);

        pref = findPreference(R.string.preference_key_notification_thanks_enable);
        drawable = VectorDrawableCompat.create(getActivity().getResources(), R.drawable.ic_usertalk_constructive, null);
        DrawableCompat.setTint(drawable, ContextCompat.getColor(getActivity(), R.color.green50));
        pref.setIcon(drawable);
    }

    private final class PollPreferenceListener implements Preference.OnPreferenceChangeListener {
        @Override public boolean onPreferenceChange(final Preference preference, Object newValue) {
            return true;
        }
    }
}
