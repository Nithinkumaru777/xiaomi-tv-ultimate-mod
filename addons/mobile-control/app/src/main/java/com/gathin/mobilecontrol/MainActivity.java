package com.gathin.mobilecontrol;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#070A14"));
        int pad = dp(48);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("📱 Mobile Mouse, Keyboard & Controller");
        title.setTextColor(Color.parseColor("#10B981"));
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        String ip = MobileControlService.getLocalIpAddress();
        String url = "http://" + ip + ":" + MobileControlService.PORT;

        TextView urlView = new TextView(this);
        urlView.setText("Open this address in your phone's browser:\n\n" + url);
        urlView.setTextColor(Color.WHITE);
        urlView.setTextSize(22f);
        urlView.setGravity(Gravity.CENTER);
        urlView.setPadding(0, dp(24), 0, dp(24));
        root.addView(urlView);

        boolean active = MobileControlService.sInstance != null;
        TextView status = new TextView(this);
        status.setText(active
                ? "Status: ONLINE — Touchpad Mouse, Keyboard, Brave Launcher & Controller Ready"
                : "Status: Waiting for MobileControlService Accessibility Service...");
        status.setTextColor(active ? Color.parseColor("#34D399") : Color.parseColor("#FBBF24"));
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(28));
        root.addView(status);

        Button openBrave = new Button(this);
        openBrave.setText("Open Brave Browser");
        openBrave.setTextColor(Color.parseColor("#052E16"));
        openBrave.setTextSize(16f);
        openBrave.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#10B981"));
        bg.setCornerRadius(dp(14));
        openBrave.setBackground(bg);
        openBrave.setPadding(dp(28), dp(14), dp(28), dp(14));
        openBrave.setOnClickListener(v -> {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.brave.browser");
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Exception ignored) {}
        });
        root.addView(openBrave);

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
