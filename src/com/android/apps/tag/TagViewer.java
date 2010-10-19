/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.apps.tag;

import com.android.apps.tag.message.NdefMessageParser;
import com.android.apps.tag.message.ParsedNdefMessage;
import com.android.apps.tag.provider.TagContract.NdefMessages;
import com.android.apps.tag.record.ParsedNdefRecord;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefTag;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * An {@link Activity} which handles a broadcast of a new tag that the device just discovered.
 */
public class TagViewer extends Activity implements OnClickListener, Handler.Callback {
    static final String TAG = "SaveTag";    
    static final String EXTRA_TAG_DB_ID = "db_id";
    static final String EXTRA_MESSAGE = "msg";

    /** This activity will finish itself in this amount of time if the user doesn't do anything. */
    static final int ACTIVITY_TIMEOUT_MS = 5 * 1000;

    Uri mTagUri;
    ImageView mIcon;
    TextView mTitle;
    CheckBox mStar;
    Button mDeleteButton;
    Button mDoneButton;
    NdefMessage[] mMessagesToSave = null;
    LinearLayout mTagContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND
        );

        setContentView(R.layout.tag_viewer);

        mTagContent = (LinearLayout) findViewById(R.id.list);
        mTitle = (TextView) findViewById(R.id.title);
        mIcon = (ImageView) findViewById(R.id.icon);
        mStar = (CheckBox) findViewById(R.id.star);
        mDeleteButton = (Button) findViewById(R.id.button_delete);
        mDoneButton = (Button) findViewById(R.id.button_done);

        mDeleteButton.setOnClickListener(this);
        mDoneButton.setOnClickListener(this);
        mIcon.setImageResource(R.drawable.ic_launcher_nfc);

        resolveIntent(getIntent());
    }

    void resolveIntent(Intent intent) {
        // Parse the intent
        String action = intent.getAction();
        if (NfcAdapter.ACTION_NDEF_TAG_DISCOVERED.equals(action)) {
            // Get the messages from the tag
            //TODO check if the tag is writable and offer to write it?
            NdefTag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            NdefMessage[] msgs = tag.getNdefMessages();
            if (msgs == null || msgs.length == 0) {
                Log.e(TAG, "No NDEF messages");
                finish();
                return;
            }

            // Setup the views
            setTitle(R.string.title_scanned_tag);
            mStar.setVisibility(View.GONE);

            // Set a timer on this activity since it wasn't created by the user
//            new Handler(this).sendEmptyMessageDelayed(0, ACTIVITY_TIMEOUT_MS);

            // Mark messages that were just scanned for saving
            mMessagesToSave = msgs;

            // Build the views for the tag
            buildTagViews(msgs);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            // Setup the views
            setTitle(R.string.title_existing_tag);
            mStar.setVisibility(View.VISIBLE);

            // Read the tag from the database asynchronously
            mTagUri = intent.getData();
            new LoadTagTask().execute(mTagUri);
        } else {
            Log.e(TAG, "Unknown intent " + intent);
            finish();
            return;
        }
    }

    void buildTagViews(NdefMessage[] msgs) {
        if (msgs == null || msgs.length == 0) {
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        LinearLayout content = mTagContent;

        // Clear out any old views in the content area, for example if you scan two tags in a row.
        content.removeAllViews();

        // Parse the first message in the list
        //TODO figure out what to do when/if we support multiple messages per tag
        ParsedNdefMessage parsedMsg = NdefMessageParser.parse(msgs[0]);

        // Build views for all of the sub records
        for (ParsedNdefRecord record : parsedMsg.getRecords()) {
            content.addView(record.getView(this, inflater, content));
            inflater.inflate(R.layout.tag_divider, content, true);
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        // If we get a new scan while looking at a tag just save off the old tag...
        if (mMessagesToSave != null) {
            saveMessages(mMessagesToSave);
            mMessagesToSave = null;
        }

        // ... and show the new one.
        resolveIntent(intent);
    }

    @Override
    public void setTitle(CharSequence title) {
        mTitle.setText(title);
    }

    @Override
    public void onClick(View view) {
        if (view == mDeleteButton) {
            if (mTagUri == null) {
                // The tag hasn't been saved yet, so indicate it shouldn't be saved
                mMessagesToSave = null;
                finish();
            } else {
                // The tag came from the database, start a service to delete it
                Intent delete = new Intent(this, TagService.class);
                delete.putExtra(TagService.EXTRA_DELETE_URI, mTagUri);
                startService(delete);
                finish();
            }
        } else if (view == mDoneButton) {
            finish();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mMessagesToSave != null) {
            saveMessages(mMessagesToSave);
        }
    }

    /**
     * Starts a service to asynchronously save the messages to the content provider.
     */
    void saveMessages(NdefMessage[] msgs) {
        Intent save = new Intent(this, TagService.class);
        save.putExtra(TagService.EXTRA_SAVE_MSGS, msgs);
        startService(save);
    }

    @Override
    public boolean handleMessage(Message msg) {
        finish();
        return true;
    }

    /**
     * Loads a tag from the database, parses it, and builds the views
     */
    final class LoadTagTask extends AsyncTask<Uri, Void, NdefMessage> {
        @Override
        public NdefMessage doInBackground(Uri... args) {
            Cursor cursor = getContentResolver().query(args[0], new String[] { NdefMessages.BYTES },
                    null, null, null);
            try {
                if (cursor.moveToFirst()) {
                    return new NdefMessage(cursor.getBlob(0));
                }
            } catch (FormatException e) {
                Log.e(TAG, "invalid tag format", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return null;
        }

        @Override
        public void onPostExecute(NdefMessage msg) {
            buildTagViews(new NdefMessage[] { msg });
        }
    }
}