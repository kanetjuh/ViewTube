package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.search;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.leanback.widget.SearchOrbView;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;

/**
 *     1) Add long click listener <br/>
 *     2) Disable short click if corresponding option enabled
 */
public class LongClickSearchOrbView extends SearchOrbView implements View.OnLongClickListener {
    private OnLongClickListener mListener2;
    private float mOrbVisualScale = 1f;
    private float mOrbVisualOffsetXPx = 0f;

    public LongClickSearchOrbView(Context context) {
        this(context, null);
    }

    public LongClickSearchOrbView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LongClickSearchOrbView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOnLongClickListener(this);
    }

    @Override
    public void onClick(View view) {
        // MOD: Disable short click if corresponding option enabled
        if (!GeneralData.instance(getContext()).isOkButtonLongPressDisabled()) {
            super.onClick(view);
        } else if (null != mListener2) {
            mListener2.onLongClick(view);
        }
    }

    @Override
    public boolean onLongClick(View view) {
        if (null != mListener2) {
            return mListener2.onLongClick(view);
        } else {
            super.onClick(view);
            return true;
        }
    }

    public void setOnOrbLongClickedListener(OnLongClickListener listener) {
        mListener2 = listener;
    }

    /**
     * Shrinks only Leanback's round orb/shadow surface while leaving the icon/avatar untouched.
     * Useful for profile artwork: Leanback's stock 52dp orb with 120% focus zoom is much larger
     * than the actual avatar artwork and otherwise creates an oversized dark flashing halo.
     */
    public void setOrbVisualScale(float scale) {
        mOrbVisualScale = scale > 0f ? scale : 1f;
        applyOrbVisualTransform();
    }

    /**
     * Moves only Leanback's round focus/background surface, not the account artwork itself.
     * This lets the focus flash be centred exactly over a compact avatar that lives inside
     * a narrower account slot.
     */
    public void setOrbVisualOffsetXDp(float offsetDp) {
        mOrbVisualOffsetXPx = offsetDp * getResources().getDisplayMetrics().density;
        applyOrbVisualTransform();
    }

    private void applyOrbVisualTransform() {
        View orb = findViewById(androidx.leanback.R.id.search_orb);
        if (orb != null) {
            orb.setScaleX(mOrbVisualScale);
            orb.setScaleY(mOrbVisualScale);
            orb.setTranslationX(mOrbVisualOffsetXPx);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        // SearchOrbView starts its own focus animation in super. Re-apply the account-specific
        // inner-orb scale after that animation is scheduled so the halo follows avatar size.
        applyOrbVisualTransform();
        post(this::applyOrbVisualTransform);
    }
}
