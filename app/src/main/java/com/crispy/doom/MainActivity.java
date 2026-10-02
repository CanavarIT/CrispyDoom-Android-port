package com.crispy.doom;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends SDLActivity {

    private static final String TAG = "CrispyDoom";

    private TouchControlsView controls;

    @Override
    protected String[] getLibraries() {
        return new String[] { "crispy" };
    }

    @Override
    protected String[] getArguments() {
        File wadFile = new File(new File(getFilesDir(), "iwad"), "doom1.wad");
        return new String[] {
            "-iwad", wadFile.getAbsolutePath()
        };
    }

    /**
     * SDL при старте сам выставляет ориентацию "по датчику" (FULL_SENSOR), из-за чего игра
     * открывалась в портрете и только потом поворачивалась. Всегда держим альбомную
     * (в обе стороны), что бы ни просил движок.
     */
    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT >= 28) {
            // не менять размер окна из-за выреза камеры
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        extractAsset("doom1.wad");
        super.onCreate(savedInstanceState);
        // Прячем системные панели сразу, до создания поверхности SDL, чтобы окно
        // не меняло размер уже после старта движка.
        hideSystemUI();

        // Экранное управление поверх SDL-поверхности
        if (mLayout != null) {
            controls = new TouchControlsView(this);
            mLayout.addView(controls, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ));

            boolean auto = false;
            try { auto = SDLActivity.supportsRelativeMouse(); } catch (Throwable ignored) { }
            controls.setAutoMode(auto);
            final TouchControlsView tc = controls;
            SDLActivity.setRelativeMouseListener(tc::onGameGrabChanged);
        }
    }

    @Override
    protected void onDestroy() {
        SDLActivity.setRelativeMouseListener(null);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (controls != null) controls.releaseAll();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(
                        WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars()
                    );
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                );
            }
        } catch (Exception e) {
            Log.w(TAG, "hideSystemUI failed: " + e.getMessage());
        }
    }

    private void extractAsset(String assetName) {
        File dir = new File(getFilesDir(), "iwad");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, assetName);
        if (out.exists() && out.length() > 0) {
            Log.i(TAG, "WAD already extracted: " + out.getAbsolutePath());
            return;
        }
        try (InputStream in = getAssets().open(assetName);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n; long total = 0;
            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
            Log.i(TAG, "WAD extracted: " + out.getAbsolutePath() + " (" + total + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract " + assetName, e);
        }
    }
}