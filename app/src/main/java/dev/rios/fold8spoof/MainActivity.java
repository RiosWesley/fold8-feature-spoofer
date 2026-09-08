package dev.rios.fold8spoof;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Entry point to start the LLM server (also used for manual start). */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, LlmServerService.class));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView tv = new TextView(this);
        tv.setText("Fold8 Spoofer + LLM server\n\nO servidor local de resumo foi iniciado em 127.0.0.1:"
                + LlmServerService.PORT + ".\nDeixe este app instalado.");
        layout.addView(tv);
        setContentView(layout);
    }
}
