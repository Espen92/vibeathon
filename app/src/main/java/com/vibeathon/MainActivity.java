package com.vibeathon;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String STATE_TAP_COUNT = "tapCount";

    private int tapCount = 0;
    private TextView messageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            tapCount = savedInstanceState.getInt(STATE_TAP_COUNT, 0);
        }

        messageView = findViewById(R.id.message);
        Button tapButton = findViewById(R.id.tap_button);
        tapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tapCount++;
                updateMessage();
            }
        });

        updateMessage();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAP_COUNT, tapCount);
    }

    private void updateMessage() {
        if (tapCount == 0) {
            messageView.setText(R.string.welcome_message);
        } else {
            messageView.setText(getResources().getQuantityString(
                    R.plurals.tap_count_message, tapCount, tapCount));
        }
    }
}
