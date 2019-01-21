package org.wikipedia.settings;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import org.wikipedia.R;
import org.wikipedia.WikipediaApp;
import org.wikipedia.concurrency.RxBus;
import org.wikipedia.events.PluggableTrasportSettingsChangedEvent;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class PluggableTransportSettingsDialog extends DialogFragment {

    private final RxBus bus = WikipediaApp.getInstance().getBus();

    private Button buttonGetAntoherBridge;
    private EditText editTextBridgeLine;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        // inflate layout and get references to views
        View view = getActivity().getLayoutInflater().inflate(R.layout.dialog_pluggable_transport_settings, null);
        editTextBridgeLine = view.findViewById(R.id.edit_bridge_line);
        String oldBridgeLine = Prefs.getPluggableTransportBridgeLine();
        editTextBridgeLine.setText(oldBridgeLine);
        buttonGetAntoherBridge = view.findViewById(R.id.button_get_another_bridge_line);
        buttonGetAntoherBridge.setOnClickListener(v -> {
            editTextBridgeLine.setText(getRandomDefaultBrideLine());
        });

        // create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.preference_title_pluggable_transport_enabled);
        builder.setView(view);
        builder.setPositiveButton("OK", (dialog, which) -> {
            String bridgeLine = editTextBridgeLine.getText().toString().trim();
            Prefs.setPluggableTransportBridgeLine(bridgeLine);
            Prefs.setPluggableTransportEnabled(bridgeLine.length() > 0);
            bus.post(new PluggableTrasportSettingsChangedEvent());
        });
        builder.setNegativeButton("cancel", (dialog, which) -> {
            Prefs.setPluggableTransportEnabled(false);
            bus.post(new PluggableTrasportSettingsChangedEvent());
        });
        return builder.create();
    }

    private String getRandomDefaultBrideLine(){
        String[] bridgeLines = getResources().getStringArray(R.array.pluggable_transports_default_bridges);
        return bridgeLines[new Random().nextInt(bridgeLines.length)];
    }
}
