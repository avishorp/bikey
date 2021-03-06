/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2015 Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jraf.android.bikey.backend.dbimport;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Xml;

import org.jraf.android.bikey.backend.provider.log.LogColumns;
import org.jraf.android.bikey.backend.provider.ride.RideColumns;
import org.jraf.android.util.log.Log;
import org.xmlpull.v1.XmlPullParser;

public class BikeyRideImporter {
    private static final String DOCUMENT_VERSION = "1";
    private static final int CONTENT_VALUES_BUFFER_SIZE = 100;

    @NonNull
    private final ContentResolver mContentResolver;
    @NonNull
    private final InputStream mInputStream;
    @Nullable
    private final RideImporterProgressListener mRideImporterProgressListener;
    private ArrayList<ContentValues> mContentValuesList;

    private enum State {
        BIKEY, RIDE, LOG,
    }

    public BikeyRideImporter(@NonNull ContentResolver contentResolver, @NonNull InputStream inputStream,
                             @Nullable RideImporterProgressListener rideImporterProgressListener) {
        mContentResolver = contentResolver;
        mInputStream = inputStream;
        mRideImporterProgressListener = rideImporterProgressListener;
    }

    public void doImport() throws IOException, ParseException {
        if (mRideImporterProgressListener != null) mRideImporterProgressListener.onImportStarted();
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(mInputStream, null);
            parser.nextTag();
            parser.require(XmlPullParser.START_TAG, null, "bikey");
            String version = parser.getAttributeValue(null, "version");
            if (!DOCUMENT_VERSION.equals(version)) {
                Log.w("Importing from an unsupported format version!  Continuing anyway, but it may fail.");
            }

            State state = State.BIKEY;
            ContentValues rideContentValues = new ContentValues();
            ContentValues logContentValues = null;
            String value;
            int valueType = -1;
            String tagName = null;
            boolean isInValue = false;
            long rideId = -1;
            long logCount = 0L;
            long logIndex = 0L;
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                switch (parser.getEventType()) {
                    case XmlPullParser.START_TAG:
                        tagName = parser.getName();

                        switch (tagName) {
                            case "ride":
                                state = State.RIDE;
                                // "logCount" tag
                                String logCountStr = parser.getAttributeValue(null, "logCount");
                                if (logCountStr == null) {
                                    // Old format didn't have this tag.  In that case, report an unknown count (-1)
                                    logCount = -1;
                                } else {
                                    logCount = Long.parseLong(logCountStr);
                                }
                                break;

                            case "logs":
                                // We have all the values about the ride: create it now
                                rideId = createRide(rideContentValues);
                                Log.d("rideId=" + rideId);
                                break;

                            case "log":
                                state = State.LOG;
                                // Save the previous log (if any)
                                if (logContentValues != null) {
                                    createLog(rideId, logContentValues);
                                    logIndex++;
                                    if (mRideImporterProgressListener != null && logIndex % 100 == 0)
                                        mRideImporterProgressListener.onLogImported(logIndex, logCount);
                                }
                                logContentValues = new ContentValues();
                                break;

                            case "_id":
                            case "ride_id":
                                // Ignore those: autoincrement ids will be used instead
                                break;

                            default:
                                if (state != State.RIDE && state != State.LOG) break;
                                // "type" tag
                                String typeStr = parser.getAttributeValue(null, "type");
                                valueType = Integer.parseInt(typeStr);
                                // Log.d("type=" + LogUtil.getConstantName(Cursor.class, valueType, "FIELD_TYPE_"));
                                isInValue = true;
                                break;
                        }
                        break;

                    case XmlPullParser.TEXT:
                        if (isInValue) {
                            value = parser.getText();

                            ContentValues contentValues;
                            switch (state) {
                                case RIDE:
                                    contentValues = rideContentValues;
                                    break;
                                case LOG:
                                default:
                                    contentValues = logContentValues;
                                    break;
                            }

                            switch (valueType) {
                                case Cursor.FIELD_TYPE_NULL:
                                    contentValues.putNull(tagName);
                                    break;

                                case Cursor.FIELD_TYPE_STRING:
                                    contentValues.put(tagName, value);
                                    break;

                                case Cursor.FIELD_TYPE_INTEGER:
                                    contentValues.put(tagName, Long.parseLong(value));
                                    break;

                                case Cursor.FIELD_TYPE_FLOAT:
                                    contentValues.put(tagName, Double.parseDouble(value));
                                    break;
                            }

                        }
                        isInValue = false;
                        break;
                }
            }
            // Save the last log (if any)
            if (logContentValues != null) {
                createLog(rideId, logContentValues);
                logIndex++;
                if (mRideImporterProgressListener != null) mRideImporterProgressListener.onLogImported(logIndex, logCount);
            }
            // Flush any remaining ContentValues
            flushContentValuesList();
            if (mRideImporterProgressListener != null) mRideImporterProgressListener.onImportFinished(RideImporterProgressListener.LogImportStatus.SUCCESS);
        } catch (Throwable t) {
            ParseException parseException = new ParseException("Could not parse xml", 0);
            parseException.initCause(t);
            if (mRideImporterProgressListener != null) mRideImporterProgressListener.onImportFinished(RideImporterProgressListener.LogImportStatus.FAIL);
            throw parseException;
        }
    }

    private long createRide(ContentValues rideContentValues) {
        Log.d();
        Uri rideUri = mContentResolver.insert(RideColumns.CONTENT_URI, rideContentValues);
        return ContentUris.parseId(rideUri);
    }

    private void createLog(long rideId, ContentValues logContentValues) {
        logContentValues.put(LogColumns.RIDE_ID, rideId);
        if (mContentValuesList == null) mContentValuesList = new ArrayList<>(CONTENT_VALUES_BUFFER_SIZE);
        mContentValuesList.add(logContentValues);
        if (mContentValuesList.size() == CONTENT_VALUES_BUFFER_SIZE) {
            flushContentValuesList();
        }
    }

    private void flushContentValuesList() {
        if (mContentValuesList == null) return;
        int size = mContentValuesList.size();
        if (size == 0) return;
        Log.d("Inserting " + size + " items");
        mContentResolver.bulkInsert(LogColumns.CONTENT_URI, mContentValuesList.toArray(new ContentValues[size]));
        mContentValuesList.clear();
    }
}
