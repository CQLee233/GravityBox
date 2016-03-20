/*
 * Copyright (C) 2012 Sven Dawitz for the CyanogenMod Project
 * Copyright (C) 2013 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.gm2.gravitybox;

import com.ceco.gm2.gravitybox.managers.BatteryInfoManager.BatteryData;
import com.ceco.gm2.gravitybox.managers.BatteryInfoManager.BatteryStatusListener;
import com.ceco.gm2.gravitybox.managers.StatusBarIconManager;
import com.ceco.gm2.gravitybox.managers.StatusBarIconManager.ColorInfo;
import com.ceco.gm2.gravitybox.managers.StatusBarIconManager.IconManagerListener;

import de.robv.android.xposed.XposedBridge;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.ImageView;

public class CmCircleBattery extends ImageView implements IconManagerListener, BatteryStatusListener {
    private static final String TAG = "GB:CircleBattery";
    private static final boolean DEBUG = false;

    public enum Style { SOLID, DASHED };

    private Handler mHandler;

    // state variables
    private boolean mAttached;      // whether or not attached to a window
    private boolean mIsCharging;    // whether or not device is currently charging
    private int     mLevel;         // current battery level
    private int     mAnimOffset;    // current level of charging animation
    private boolean mIsAnimating;   // stores charge-animation status to reliably remove callbacks
    private int     mDockLevel;     // current dock battery level
    private boolean mDockIsCharging;// whether or not dock battery is currently charging
    private boolean mPercentage;    // whether to show percentage

    private int     mCircleSize;    // draw size of circle. read rather complicated from
                                    // another status bar icon, so it fits the icon size
                                    // no matter the dps and resolution
    private RectF   mRectLeft;      // contains the precalculated rect used in drawArc(), derived from mCircleSize
    private Float   mTextLeftX;     // precalculated x position for drawText() to appear centered
    private Float   mTextY;         // precalculated y position for drawText() to appear vertical-centered

    // quiet a lot of paint variables. helps to move cpu-usage from actual drawing to initialization
    private Paint   mPaintFont;
    private Paint   mPaintGray;
    private Paint   mPaintSystem;
    private Paint   mPaintRed;

    // style
    private float mStrokeWidthFactor;
    private DashPathEffect mPathEffect;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    // runnable to invalidate view via mHandler.postDelayed() call
    private final Runnable mInvalidate = new Runnable() {
        public void run() {
            if(mAttached) {
                invalidate();
            }
        }
    };

    // keeps track of current battery level and charger-plugged-state
    @Override
    public void onBatteryStatusChanged(BatteryData batteryData) {
        mLevel = batteryData.level;
        mIsCharging = batteryData.charging;
        if (mAttached) {
            invalidate();
        }
    }

    /***
     * Start of CircleBattery implementation
     */
    public CmCircleBattery(Context context) {
        this(context, null);
    }

    public CmCircleBattery(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CmCircleBattery(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mHandler = new Handler();

        // initialize and setup all paint variables
        // stroke width is later set in initSizeBasedStuff()
        Resources res = getResources();

        mPaintFont = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintFont.setDither(true);
        mPaintFont.setStyle(Paint.Style.STROKE);
        mPaintFont.setTextAlign(Align.CENTER);
        mPaintFont.setFakeBoldText(true);
        mPaintFont.setColor(res.getColor(android.R.color.holo_blue_dark));

        mPaintGray = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintGray.setStrokeCap(Paint.Cap.BUTT);
        mPaintGray.setDither(true);
        mPaintGray.setStrokeWidth(0);
        mPaintGray.setStyle(Paint.Style.STROKE);
        mPaintGray.setColor(0x4DFFFFFF);

        mPaintSystem = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintSystem.setStrokeCap(Paint.Cap.BUTT);
        mPaintSystem.setDither(true);
        mPaintSystem.setStrokeWidth(0);
        mPaintSystem.setStyle(Paint.Style.STROKE);
        mPaintSystem.setColor(mPaintFont.getColor());

        mPaintRed = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaintRed.setStrokeCap(Paint.Cap.BUTT);
        mPaintRed.setDither(true);
        mPaintRed.setStrokeWidth(0);
        mPaintRed.setStyle(Paint.Style.STROKE);
        mPaintRed.setColor(res.getColor(android.R.color.holo_red_light));

        mPercentage = false;

        setStyle(Style.SOLID);
    }

    public void setPercentage(boolean enable) {
        mPercentage = enable;
        if (mAttached) {
            invalidate();
        }
    }

    public void setStyle(Style style) {
        switch (style) {
            case SOLID:
                mStrokeWidthFactor = 7.5f;
                mPathEffect = null;
                break;
            case DASHED:
                mStrokeWidthFactor = 7f;
                mPathEffect = new DashPathEffect(new float[]{3,2},0);
                break;
        }
        mRectLeft = null;
        if (mAttached) {
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mHandler.postDelayed(mInvalidate, 250);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
            mRectLeft = null; // makes sure, size based variables get
                                // recalculated on next attach
            mCircleSize = 0;    // makes sure, mCircleSize is reread from icons on
                                // next attach
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mCircleSize == 0) {
            initSizeMeasureIconHeight();
        }

        setMeasuredDimension(mCircleSize + getPaddingLeft(), mCircleSize);
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        initSizeBasedStuff();
    }

    private void drawCircle(Canvas canvas, int level, int animOffset, float textX, RectF drawRect) {
        final Paint usePaint = level <= 14 ? mPaintRed : mPaintSystem;
        usePaint.setAntiAlias(true);
        usePaint.setPathEffect(mPathEffect);

        // pad circle percentage to 100% once it reaches 97%
        // for one, the circle looks odd with a too small gap,
        // for another, some phones never reach 100% due to hardware design
        int padLevel = level;
        if (padLevel >= 97) {
            padLevel = 100;
        }

        // draw thin gray ring first
        canvas.drawArc(drawRect, 270, 360, false, mPaintGray);
        // draw colored arc representing charge level
        canvas.drawArc(drawRect, 270 + animOffset, 3.6f * padLevel, false, usePaint);
        // if chosen by options, draw percentage text in the middle
        // always skip percentage when 100, so layout doesnt break
        if (level < 100 && mPercentage) {
            mPaintFont.setColor(usePaint.getColor());
            canvas.drawText(Integer.toString(level), textX, mTextY, mPaintFont);
        }

    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mRectLeft == null) {
            initSizeBasedStuff();
        }

        updateChargeAnim();

        drawCircle(canvas, mLevel, (mIsCharging ? mAnimOffset : 0), mTextLeftX, mRectLeft);
    }

    /***
     * updates the animation counter
     * cares for timed callbacks to continue animation cycles
     * uses mInvalidate for delayed invalidate() callbacks
     */
    private void updateChargeAnim() {
        if (!(mIsCharging || mDockIsCharging) || (mLevel >= 97 && mDockLevel >= 97)) {
            if (mIsAnimating) {
                mIsAnimating = false;
                mAnimOffset = 0;
                mHandler.removeCallbacks(mInvalidate);
            }
            return;
        }

        mIsAnimating = true;

        if (mAnimOffset > 360) {
            mAnimOffset = 0;
        } else {
            mAnimOffset += 3;
        }

        mHandler.removeCallbacks(mInvalidate);
        mHandler.postDelayed(mInvalidate, 50);
    }

    /***
     * initializes all size dependent variables
     * sets stroke width and text size of all involved paints
     * YES! i think the method name is appropriate
     */
    private void initSizeBasedStuff() {
        if (mCircleSize == 0) {
            initSizeMeasureIconHeight();
        }

        mPaintFont.setTextSize(mCircleSize / 2.0f);

        float strokeWidth = mCircleSize / mStrokeWidthFactor;
        mPaintRed.setStrokeWidth(strokeWidth);
        mPaintSystem.setStrokeWidth(strokeWidth);
        mPaintGray.setStrokeWidth(strokeWidth);

        // calculate rectangle for drawArc calls
        int pLeft = getPaddingLeft();
        mRectLeft = new RectF(pLeft + strokeWidth / 2.0f, 0 + strokeWidth / 2.0f, mCircleSize
                - strokeWidth / 2.0f + pLeft, mCircleSize - strokeWidth / 2.0f);

        // calculate Y position for text
        Rect bounds = new Rect();
        mPaintFont.getTextBounds("99", 0, "99".length(), bounds);
        mTextLeftX = mCircleSize / 2.0f + getPaddingLeft();
        // the +1dp at end of formular balances out rounding issues. works out on all resolutions
        mTextY = mCircleSize / 2.0f + (bounds.bottom - bounds.top) / 2.0f - strokeWidth / 2.0f +
                getResources().getDisplayMetrics().density;

        // force new measurement for wrap-content xml tag
        measure(0, 0);
    }

    /***
     * we need to measure the size of the circle battery by checking another
     * resource. unfortunately, those resources have transparent/empty borders
     * so we have to count the used pixel manually and deduct the size from
     * it. quiet complicated, but the only way to fit properly into the
     * statusbar for all resolutions
     */
    private void initSizeMeasureIconHeight() {
        final Resources res = getResources();
        mCircleSize = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16,
                res.getDisplayMetrics()));
        mCircleSize = Math.round(mCircleSize / 2f) * 2;
        if (DEBUG) log("mCircleSize = " + mCircleSize + "px");
    }

    public void setColor(int color) {
        mPaintSystem.setColor(color);
        mPaintFont.setColor(color);
        invalidate();
    }

    public void setLowProfile(boolean lightsOut) {
        final int alpha = lightsOut ? 127 : 255;
        mPaintSystem.setAlpha(alpha);
        mPaintGray.setAlpha(alpha);
        mPaintFont.setAlpha(alpha);
        mPaintRed.setAlpha(alpha);
        invalidate();
    }

    @Override
    public void onIconManagerStatusChanged(int flags, ColorInfo colorInfo) {
        if ((flags & StatusBarIconManager.FLAG_ICON_COLOR_CHANGED) != 0) {
            setColor(colorInfo.coloringEnabled ?
                    colorInfo.iconColor[0] : colorInfo.defaultIconColor);
        } else if ((flags & StatusBarIconManager.FLAG_LOW_PROFILE_CHANGED) != 0) {
            setLowProfile(colorInfo.lowProfile);
        }
    }
}
