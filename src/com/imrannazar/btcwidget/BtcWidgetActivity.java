package com.imrannazar.btcwidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.RemoteViews;
import android.widget.Spinner;

import com.imrannazar.btcwidget.R;

public class BtcWidgetActivity extends Activity
{
    public static final String PREFS_NAME = "com.imrannazar.btcwidget.BtcWidgetProvider";

    int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    SharedPreferences pref;
    SharedPreferences.Editor prefedit;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        setContentView(R.layout.main);

        Intent i = getIntent();
        Bundle extras = i.getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
        }

        final Spinner sel = (Spinner)findViewById(R.id.currency_sel);

        pref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefedit = pref.edit();
        ArrayAdapter arr = (ArrayAdapter)sel.getAdapter();
        sel.setSelection(arr.getPosition(pref.getString("btc_" + widgetId + "_currency", getString(R.string.default_currency))));

        findViewById(R.id.save_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final Context ctx = BtcWidgetActivity.this;

                prefedit.putString("btc_" + widgetId + "_currency", sel.getSelectedItem().toString());
                prefedit.commit();

                new BtcWidgetProvider().onUpdate(ctx, AppWidgetManager.getInstance(ctx), new int[] {widgetId});

                Intent result = new Intent();
                result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
                setResult(RESULT_OK, result);
                finish();
            }
        });
    }
}

