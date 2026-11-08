package com.litmaspatra.docos.ui;

import android.content.Context;
import android.view.View;

import java.io.File;

/** A document type plugged into the viewer screen. */
public interface DocView {
    interface Host {
        void dirtyChanged();
        void toast(String msg);
    }

    /** Parses the file. Runs on a background thread. */
    void load(File f) throws Exception;

    /** Builds the content view. UI thread, after load(). */
    View createView(Context c) throws Exception;

    /** Optional bar shown under the content (null if none). */
    View bottomBar();

    boolean dirty();

    /** UI thread, just before save() runs on a background thread: snapshot anything the save needs. */
    void beforeSave();

    /** Writes the edited document to out. Background thread. */
    void save(File in, File out) throws Exception;

    /** True when save() may fail for some files and saveFallback() can still produce a copy. */
    boolean hasFallback();

    void saveFallback(File in, File out) throws Exception;

    /** Called on the UI thread after a successful save; newFile is the file that now holds the saved document. */
    void onSaved(File newFile);

    int position();

    void restore(int pos);

    String[] menu();

    void onMenu(int index);

    void close();
}
