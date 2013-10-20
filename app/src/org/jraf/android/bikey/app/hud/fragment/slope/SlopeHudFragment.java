/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 * 
 * Copyright (C) 2013 Benoit 'BoD' Lubek (BoD@JRAF.org)
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
package org.jraf.android.bikey.app.hud.fragment.slope;

import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.jraf.android.bikey.R;
import org.jraf.android.bikey.app.hud.fragment.SimpleHudFragment;
import org.jraf.android.bikey.backend.location.LocationManager;
import org.jraf.android.bikey.backend.location.LocationManager.StatusListener;
import org.jraf.android.bikey.backend.location.SlopeMeter;
import org.jraf.android.bikey.util.UnitUtil;

public class SlopeHudFragment extends SimpleHudFragment {
    private TextView mTxtDebugLastSpeed;

    public static SlopeHudFragment newInstance() {
        return new SlopeHudFragment();
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.hud_speed;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTxtDebugLastSpeed = (TextView) view.findViewById(R.id.txtDebugLastSpeed);
    }

    @Override
    public void onStart() {
        super.onStart();
        // GPS status
        LocationManager.get().addStatusListener(mGpsStatusListener);

        // Speed updates
        mSlopeMmeter.startListening();
    }

    @Override
    public void onStop() {
        // GPS status
        LocationManager.get().removeStatusListener(mGpsStatusListener);

        // Speed updates
        mSlopeMmeter.stopListening();
        super.onStop();
    }

    private SlopeMeter mSlopeMmeter = new SlopeMeter() {
        @Override
        public void onLocationChanged(Location location) {
            super.onLocationChanged(location);
            setText(UnitUtil.formatSlope(getSlope()));
            if (mDebugInfo.lastLocationPair != null) mTxtDebugLastSpeed.setText("" + mDebugInfo.lastLocationPair.getSlope() * 100f);
        }
    };

    private StatusListener mGpsStatusListener = new StatusListener() {
        @Override
        public void onStatusChanged(boolean active) {
            setTextEnabled(active);
        }
    };
}