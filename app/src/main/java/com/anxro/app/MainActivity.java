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

        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
        ActivityCompat.requestPermissions(this, perms, 100);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(), "Android");
        setContentView(webView);

        loadApp();
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
            if (!indexFile.exists()) extractAssetsZip();
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
        byte[] buffer = new byte[1024]; // FIXED LINE 97
        while ((ze = zis.getNextEntry())!= null) {
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

        InputStream jsonIn = getAssets().open("app-version.json");
        String json = new BufferedReader(new InputStreamReader(jsonIn)).readLine();
        JSONObject obj = new JSONObject(json);
        prefs.edit().putInt("version", obj.getInt("version")).apply();
        jsonIn.close();
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void showNotification(String title, String body) {
            runOnUiThread(() -> {
                int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                int iconRes = (hour >= 6 && hour < 18)? R.drawable.day_logo : R.drawable.night_logo;
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

    class SilentUpdateTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new URL(VERSION_URL).openStream()));
                String json = br.readLine();
                br.close();

                JSONObject obj = new JSONObject(json);
                int remoteVersion = obj.getInt("version");
                String zipUrl = obj.getString("zip_url");
                int localVersion = prefs.getInt("version", 0);

                if (remoteVersion > localVersion) {
                    File zipFile = new File(getFilesDir(), "update.zip");
                    InputStream in = new URL(zipUrl).openStream();
                    FileOutputStream out = new FileOutputStream(zipFile);
                    byte[] buf = new byte[1024]; // FIXED LINE 166
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    out.close();
                    in.close();

                    File appDir = new File(getFilesDir(), APP_FOLDER);
                    deleteRecursive(appDir);
                    unzip(zipFile, appDir);
                    zipFile.delete();

                    prefs.edit().putInt("version", remoteVersion).apply();
                    return true;
                }
            } catch (Exception e) {
            }
            return false;
        }
    }

    void unzip(File zipFile, File targetDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        ZipEntry ze;
        byte[] buffer = new byte[1024]; // FIXED LINE 195
        while ((ze = zis.getNextEntry())!= null) {
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
