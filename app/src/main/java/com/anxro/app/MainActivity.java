package com.anxro.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.Manifest;
import android.os.AsyncTask;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import android.os.Build;
import android.graphics.BitmapFactory;
import java.io.*;
import java.net.URL;
import java.util.Calendar;
import java.util.zip.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    WebView webView;
    NotificationManager notificationManager;
    SharedPreferences prefs;

    String VERSION_URL = "https://jithinrajrk147-glitch.github.io/Anxro/app-version.json";
    String APP_FOLDER = "app";
    String CHANNEL_ID = "anxro_local";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("anxro", MODE_PRIVATE);

        // 1. Permissions
        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
        ActivityCompat.requestPermissions(this, perms, 100);

        // 2. Notifications
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        // 3. WebView setup
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(), "Android");
        setContentView(webView);

        // 4. Load app IMMEDIATELY from bundled files - no internet
        loadApp();

        // 5. Check for update in background - user keeps using old version
        new SilentUpdateTask().execute();
    }

    void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ANXRO Alerts", NotificationManager.IMPORTANCE_DEFAULT
            );
            notificationManager.createNotificationChannel(channel);
        }
    }

    void loadApp() {
        File appDir = new File(getFilesDir(), APP_FOLDER);
        File indexFile = new File(appDir, "index.html");
        try {
            // First run: extract bundled zip from APK assets
            if (!indexFile.exists()) {
                extractAssetsZip();
            }
            // Load instantly - offline
            webView.loadUrl("file://" + indexFile.getAbsolutePath());
        } catch (Exception e) {
            webView.loadData("<h1>ANXRO</h1><p>Failed to load</p>", "text/html", "UTF-8");
        }
    }

    void extractAssetsZip() throws Exception {
        InputStream in = getAssets().open("app.zip");
        File appDir = new File(getFilesDir(), APP_FOLDER);
        appDir.mkdirs();
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry ze;
        byte[] buffer = new byte;
        while ((ze = zis.getNextEntry()) != null) {
            File file = new File(appDir, ze.getName());
            if (ze.isDirectory()) {
                file.mkdirs();
            } else {
                new File(file.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(file);
                int len;
                while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                fos.close();
            }
        }
        zis.close();
        in.close();

        // Save bundled version number from assets
        InputStream jsonIn = getAssets().open("app-version.json");
        String json = new BufferedReader(new InputStreamReader(jsonIn)).readLine();
        JSONObject obj = new JSONObject(json);
        prefs.edit().putInt("version", obj.getInt("version")).apply();
        jsonIn.close();
    }

    // JS Interface for local notifications
    public class WebAppInterface {
        @JavascriptInterface
        public void showNotification(String title, String body) {
            runOnUiThread(() -> {
                int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                int iconRes = (hour >= 6 && hour < 18) ? R.drawable.day_logo : R.drawable.night_logo;
                Intent intent = new Intent(MainActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                    MainActivity.this, 0, intent, PendingIntent.FLAG_IMMUTABLE
                );
                NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                   .setSmallIcon(iconRes)
                   .setLargeIcon(BitmapFactory.decodeResource(getResources(), iconRes))
                   .setContentTitle(title)
                   .setContentText(body)
                   .setAutoCancel(true)
                   .setContentIntent(pendingIntent);
                notificationManager.notify((int) System.currentTimeMillis(), builder.build());
            });
        }
    }

    // Background silent update - user uses old version while this runs
    class SilentUpdateTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                // 1. Check remote version.json
                BufferedReader br = new BufferedReader(new InputStreamReader(new URL(VERSION_URL).openStream()));
                String json = br.readLine();
                br.close();

                JSONObject obj = new JSONObject(json);
                int remoteVersion = obj.getInt("version");
                String zipUrl = obj.getString("zip_url");
                int localVersion = prefs.getInt("version", 0);

                // 2. If new version available
                if (remoteVersion > localVersion) {
                    // Download new zip in background
                    File zipFile = new File(getFilesDir(), "update.zip");
                    InputStream in = new URL(zipUrl).openStream();
                    FileOutputStream out = new FileOutputStream(zipFile);
                    byte[] buf = new byte;
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    out.close();
                    in.close();

                    // 3. Delete old app folder and replace with new
                    File appDir = new File(getFilesDir(), APP_FOLDER);
                    deleteRecursive(appDir);
                    unzip(zipFile, appDir);
                    zipFile.delete();

                    // 4. Save new version
                    prefs.edit().putInt("version", remoteVersion).apply();
                    return true; // Update done
                }
            } catch (Exception e) {
                // No internet or error - silent fail, keep using old version
            }
            return false;
        }
        
        // Don't reload webview - next app restart will use new version
        // User continues using old version until they close/reopen app
    }

    void unzip(File zipFile, File targetDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        ZipEntry ze;
        byte[] buffer = new byte;
        while ((ze = zis.getNextEntry()) != null) {
            File file = new File(targetDir, ze.getName());
            if (ze.isDirectory()) {
                file.mkdirs();
            } else {
                new File(file.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(file);
                int len;
                while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                fos.close();
            }
        }
        zis.close();
    }

    void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            for (File child : fileOrDir.listFiles()) deleteRecursive(child);
        }
        fileOrDir.delete();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
