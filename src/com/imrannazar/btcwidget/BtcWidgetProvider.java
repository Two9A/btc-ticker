package com.imrannazar.btcwidget;
 
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.ImageView;
import android.widget.RemoteViews;
 
public class BtcWidgetProvider extends AppWidgetProvider {
    public static final String PREFS_NAME = "com.imrannazar.btcwidget.BtcWidgetProvider";
    public static String PRICE_RENDER = "com.imrannazar.btcwidget.PRICE_RENDER";
    public static String PRICE_FETCH  = "com.imrannazar.btcwidget.PRICE_FETCH";
    public static final String TAG    = "BtcWidget";
    public static int REFRESH_TIME    = 60000;  // One minute
    public static int GRAPH_WIDTH     = 540;
    public static int GRAPH_HEIGHT    = 160;

    /**
     * onEnabled: Fired when the first instance of the widget shows up
     */
    @Override
    public void onEnabled(Context ctx) {
        super.onEnabled(ctx);

        Log.i(TAG, "onEnabled");
        AlarmManager alarm = (AlarmManager)ctx.getSystemService(ctx.ALARM_SERVICE);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(System.currentTimeMillis());
        cal.add(Calendar.SECOND, 1);
        alarm.setRepeating(AlarmManager.RTC, cal.getTimeInMillis(), REFRESH_TIME, createIntent(ctx, PRICE_FETCH));
    }

    /**
     * onDisabled: Fired when the last instance of the widget is removed
     * Cancels the repeating timer
     */
    @Override
    public void onDisabled(Context ctx) {
        super.onDisabled(ctx);

        Log.i(TAG, "onDisabled");
        AlarmManager alarm = (AlarmManager)ctx.getSystemService(ctx.ALARM_SERVICE);
        alarm.cancel(createIntent(ctx, PRICE_FETCH));
    }

    /**
     * onReceive: Fired when an intent is broadcast
     * Used to pass messages from the network thread to UI
     */
    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);

        AppWidgetManager awManager = AppWidgetManager.getInstance(ctx);

        if (PRICE_FETCH.equals(intent.getAction())) {
            Log.i(TAG, "Received FETCH intent");
            ConnectivityManager conn = (ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo net = conn.getActiveNetworkInfo();
            SharedPreferences pref = ctx.getSharedPreferences(PREFS_NAME, ctx.MODE_PRIVATE);

            ComponentName thisWidget = new ComponentName(ctx.getPackageName(), getClass().getName());
            int awIds[] = awManager.getAppWidgetIds(thisWidget);
            for (int awId: awIds) {
                Log.i(TAG, "Fetching for "+awId);

                // We were asked to fetch prices; only do that if the network is up
                if (net != null && net.isConnected()) {
                    String currency = pref.getString("btc_" + awId + "_currency", ctx.getString(R.string.default_currency));
                    new PriceFetchTask()
                        .setContext(ctx)
                        .setWidgetId(awId)
                        .execute(ctx.getString(R.string.fetch_url) + currency.substring(0, 3));
                } else {
                    try {
                        createIntent(ctx, PRICE_RENDER, awId, "{\"error\":\"Not Online\"}").send();
                    }
                    catch (PendingIntent.CanceledException e) {
                        // Should never happen, of course
                        Log.e(TAG, "Rendering intent cancelled", e);
                    }
                }
            }
        }
        else if (PRICE_RENDER.equals(intent.getAction())) {
            int awId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (awId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                Log.i(TAG, "Received RENDER intent for "+awId);

                // We were asked (by the network thread) to render the widget given a lump of JSON
                String msg = "";
                float last = 0.0f;
                float[] prices = new float[10];

                try {
                    JSONObject json = new JSONObject(intent.getStringExtra("json"));

                    if (json.has("error")) {
                        msg = json.getString("error");
                    }
                    else if (json.has("data")) {
                        JSONArray data = json.getJSONArray("data");
                        for (int i = 0; i < data.length(); i++) {
                            prices[i] = Float.parseFloat(data.getString(i));
                        }
                        last = prices[data.length() - 1];
                    }
                    else {
                        msg = "Decode error";
                    }
                }
                catch (JSONException e) {
                    // Again, should never happen
                    Log.e(TAG, "Unparseable JSON", e);
                }

                if (msg.length() > 0) {
                    render(ctx, awManager, awId, msg);
                } else {
                    render(ctx, awManager, awId, last, prices);
                }
            } else {
                Log.e(TAG, "Received a RENDER intent with no widget ID");
            }
        }
    }

    /**
     * The network thread
     */
    private class PriceFetchTask extends AsyncTask<String, Void, String> {
        private Context ctx;
        private int awId;

        public PriceFetchTask setContext(Context c) {
            ctx = c;
            return this;
        }

        public PriceFetchTask setWidgetId(int id) {
            awId = id;
            return this;
        }

        @Override
        protected String doInBackground(String... urls) {
            try {
                InputStream is;
                Reader rd;
                char[] buf = new char[4096];
                
                Log.i(TAG, "Fetching "+urls[0]);

                URL url = new URL(urls[0]);
                HttpURLConnection conn = (HttpURLConnection)url.openConnection();
                conn.setReadTimeout(10000);
                conn.setConnectTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setDoInput(true);

                conn.connect();
                is = conn.getInputStream();
                rd = new InputStreamReader(is, "UTF-8");
                rd.read(buf);
                return new String(buf);
            }
            catch (IOException e) {
                Log.e(TAG, "Exception during fetch", e);
                return "{\"error\":\"Fetch failed\"}";
            }
        }

        @Override
        protected void onPostExecute(String result) {
            try {
                createIntent(ctx, PRICE_RENDER, awId, result).send();
            }
            catch (PendingIntent.CanceledException e) {
                // Should never happen, of course
                Log.e(TAG, "Rendering intent cancelled", e);
            }
        }
    }

    /**
     * createIntent: Broadcast an intent to the system
     * The overridden method is used by the network thread to pass JSON in
     */
    private PendingIntent createIntent(Context ctx, String intentName) {
        Intent i = new Intent(intentName);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        return pi;
    }

    private PendingIntent createIntent(Context ctx, String intentName, int appWidgetId) {
        Intent i = new Intent(intentName);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        return pi;
    }

    private PendingIntent createIntent(Context ctx, String intentName, int appWidgetId, String json) {
        Intent i = new Intent(intentName);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        i.putExtra("json", json);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        return pi;
    }

    /**
     * render: As one may guess, render the insides of the widget
     */
    private void render(Context ctx, AppWidgetManager awManager, int awId, float last, float[] prices) {
        SharedPreferences pref = ctx.getSharedPreferences(PREFS_NAME, ctx.MODE_PRIVATE);
        String currency = pref.getString("btc_" + awId + "_currency", ctx.getString(R.string.default_currency));

        Pattern r = Pattern.compile("^([A-Z]{3}).*\\((.+)\\)$");
        Matcher m = r.matcher(currency);

        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.btcwidget);

        if (m.find()) {
            views.setTextViewText(R.id.btcprice, m.group(2) + String.format("%,.2f", last));

            float min = 1e10f, max = 0.0f, scale = 0.0f, points[] = new float[36];
            for (float price: prices) {
                if (price < min) min = price;
                if (price > max) max = price;
            }
            scale = (max - min) / GRAPH_HEIGHT;

            points[0]  = 0;
            points[1]  = GRAPH_HEIGHT - Math.abs((min - prices[0]) / scale);
            points[34] = GRAPH_WIDTH;
            points[35] = GRAPH_HEIGHT - Math.abs((min - prices[9]) / scale);

            for (int i = 1; i < 9; i++) {
                points[i*4-2] = i * GRAPH_WIDTH / 10;
                points[i*4-1] = GRAPH_HEIGHT - Math.abs((min - prices[i]) / scale);
                points[i*4+0] = i * GRAPH_WIDTH / 10;
                points[i*4+1] = GRAPH_HEIGHT - Math.abs((min - prices[i]) / scale);
            }

            Paint p = new Paint();
            p.setAntiAlias(true);
            p.setStyle(Style.STROKE);
            p.setStrokeWidth(8);
            p.setColor(0xCCFFFFFF);

            Bitmap bmp = Bitmap.createBitmap(GRAPH_WIDTH, GRAPH_HEIGHT, Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawLines(points, p);

            views.setImageViewBitmap(R.id.btcgraph, bmp);
        }

        awManager.updateAppWidget(awId, views);
    }

    private void render(Context ctx, AppWidgetManager awManager, int awId, String msg) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.btcwidget);
        views.setTextViewText(R.id.btcprice, msg);
        awManager.updateAppWidget(awId, views);
    }
}

