package com.vibeathon;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String STATE_TAPPED = "tapped";

    private boolean tapped = false;
    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        message = findViewById(R.id.message);
        Button button = findViewById(R.id.button);

        if (savedInstanceState != null) {
            tapped = savedInstanceState.getBoolean(STATE_TAPPED, false);
        }
        updateMessage();

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tapped = !tapped;
                updateMessage();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_TAPPED, tapped);
    }

    private void updateMessage() {
        message.setText(tapped ? R.string.message_tapped : R.string.message_hello);
    }
}
