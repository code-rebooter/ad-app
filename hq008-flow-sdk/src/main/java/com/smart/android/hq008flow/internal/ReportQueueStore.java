package com.smart.android.hq008flow.internal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Transactional queue: fresh reads, conditional ACK and one-time legacy import across processes. */
final class ReportQueueStore {
    private static final Map<String, ReportQueueStore> INSTANCES = new HashMap<>();
    private final Helper helper;
    private final Context context;
    private final String legacyPreferences;

    static synchronized ReportQueueStore open(Context context, String preferences) {
        Context app = context.getApplicationContext();
        String path = app.getDatabasePath(preferences + ".db").getAbsolutePath();
        ReportQueueStore store = INSTANCES.get(path);
        if (store == null) {
            store = new ReportQueueStore(app, preferences);
            INSTANCES.put(path, store);
        }
        return store;
    }

    private ReportQueueStore(Context context, String preferences) {
        this.context = context;
        this.legacyPreferences = preferences;
        this.helper = new Helper(context, preferences + ".db");
    }

    private SQLiteDatabase database() {
        SQLiteDatabase db = helper.getWritableDatabase();
        // The marker and imported rows commit together. A second process or a
        // restart must never re-import a legacy value after its new queue ACK.
        db.beginTransaction();
        try {
            boolean imported;
            try (Cursor cursor = db.rawQuery("SELECT 1 FROM queue_metadata WHERE name='legacy_imported'", null)) {
                imported = cursor.moveToFirst();
            }
            if (!imported) {
                for (Map.Entry<String, ?> entry : context.getSharedPreferences(
                        legacyPreferences, Context.MODE_PRIVATE).getAll().entrySet()) {
                    if (!(entry.getValue() instanceof String)) continue;
                    ContentValues values = new ContentValues();
                    values.put("report_key", entry.getKey());
                    values.put("payload", (String) entry.getValue());
                    db.insertWithOnConflict("reports", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.execSQL("INSERT INTO queue_metadata(name) VALUES('legacy_imported')");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return db;
    }

    void put(String key, String payload) {
        ContentValues values = new ContentValues();
        values.put("report_key", key);
        values.put("payload", payload);
        if (database().insertWithOnConflict("reports", null, values,
                SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw new IllegalStateException("Unable to persist report " + key);
        }
    }

    String read(String key) {
        try (Cursor cursor = database().query("reports", new String[]{"payload"},
                "report_key=?", new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    List<String> keys() {
        List<String> keys = new ArrayList<>();
        try (Cursor cursor = database().query("reports", new String[]{"report_key"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) keys.add(cursor.getString(0));
        }
        return keys;
    }

    void acknowledge(String key, String payload) {
        // Comparison and delete are one database operation, including across PIDs.
        database().delete("reports", "report_key=? AND payload=?", new String[]{key, payload});
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context, String name) { super(context, name, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE reports(report_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE queue_metadata(name TEXT PRIMARY KEY NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported report queue schema upgrade");
        }
    }
}
