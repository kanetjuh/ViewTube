package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.PageRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowHeaderPresenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class IconHeaderItemPresenter extends RowHeaderPresenter {
    private static final String TAG = IconHeaderItemPresenter.class.getSimpleName();
    private float mUnselectedAlpha;
    private final int mResId;
    private final String mIconUrl;
    private Drawable mDefaultIcon;

    public IconHeaderItemPresenter(int resId, String iconUrl) {
        mResId = resId;
        mIconUrl = iconUrl;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup) {
        mUnselectedAlpha = viewGroup.getResources()
                .getFraction(R.fraction.lb_browse_header_unselect_alpha, 1, 1);
        LayoutInflater inflater = (LayoutInflater) viewGroup.getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mDefaultIcon = new ColorDrawable(ContextCompat.getColor(viewGroup.getContext(), R.color.lb_grey));

        View view = inflater.inflate(R.layout.icon_header_item, null);
        // YouTube-style rail: the icon column must stay fully opaque even when the row is not selected.
        // Collapsed/expanded transparency belongs to the rail background only, never to its children.
        view.setAlpha(1.0f);

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        HeaderItem headerItem;

        if (item instanceof PageRow) {
            headerItem = ((PageRow) item).getHeaderItem();
        } else {
            headerItem = ((ListRow) item).getHeaderItem();
        }

        View rootView = viewHolder.view;
        rootView.setFocusable(true);
        rootView.setAlpha(1.0f);

        ImageView iconView = rootView.findViewById(R.id.header_icon);
        if (iconView != null) {
            iconView.setAlpha(1.0f);
            if (mIconUrl != null) {
                Glide.with(rootView.getContext())
                        .load(mIconUrl)
                        .apply(ViewUtil.glideOptions().error(mDefaultIcon))
                        .listener(mErrorListener)
                        .into(iconView);

                //ViewUtil.makeMonochrome(iconView);
            } else {
                Drawable icon = mResId > 0 ? ContextCompat.getDrawable(rootView.getContext(), mResId) : mDefaultIcon;
                iconView.setImageDrawable(createNavigationStateDrawable(rootView.getContext(), icon));
            }
        }

        TextView label = rootView.findViewById(R.id.header_label);
        if (label != null) {
            label.setText(headerItem.getName());
        }
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        // NOP
    }

    // TODO: This is a temporary fix. Remove me when leanback onCreateViewHolder no longer sets the
    // mUnselectAlpha, and also assumes the xml inflation will return a RowHeaderView.
    @Override
    protected void onSelectLevelChanged(RowHeaderPresenter.ViewHolder holder) {
        // Do not let Leanback dim the complete header row. Doing so also fades the icon,
        // which makes the collapsed rail look transparent. Selection is already communicated
        // by the focus surface, so the navigation icon itself stays at full brightness.
        holder.view.setAlpha(1.0f);

        ImageView iconView = holder.view.findViewById(R.id.header_icon);
        if (iconView != null) {
            iconView.setAlpha(1.0f);
        }
    }


    /**
     * Builds the two visual weights used by the modern collapsed rail without requiring a
     * second asset for every SmartTube section icon. The original resource is the filled
     * selected/current icon. The default state is an outline derived from the drawable's alpha
     * edge, so Home, Shorts, Kids, Sports, custom built-in sections, etc. all behave consistently.
     * URL/channel artwork is intentionally left untouched in onBindViewHolder.
     */
    private Drawable createNavigationStateDrawable(Context context, Drawable source) {
        if (source == null) {
            return mDefaultIcon;
        }

        Drawable filled = cloneDrawable(source);
        Drawable outline = createOutlineDrawable(context, cloneDrawable(source));
        if (outline == null) {
            return filled;
        }

        StateListDrawable states = new StateListDrawable();
        // state_activated is the persistent "section currently on-screen" state. Leanback may
        // clear state_selected when focus leaves the rail, so relying on selected alone made Home
        // fall back to the thin outline while the user was scrolling Home videos.
        states.addState(new int[] { android.R.attr.state_activated }, filled);
        states.addState(new int[] { android.R.attr.state_selected }, filled);
        states.addState(new int[] {}, outline);
        return states;
    }

    private Drawable cloneDrawable(Drawable source) {
        if (source == null) {
            return null;
        }
        Drawable.ConstantState state = source.getConstantState();
        return state != null ? state.newDrawable().mutate() : source.mutate();
    }

    /**
     * Converts any local icon into a thin inner-edge outline. It is rendered once when the
     * header binds; the resulting tiny bitmap is then reused by Android's drawable state system.
     */
    private Drawable createOutlineDrawable(Context context, Drawable source) {
        if (source == null) {
            return null;
        }

        final int size = 96; // supersampled so the 20-22dp TV glyph stays smooth
        Bitmap rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rendered);
        source.setBounds(0, 0, size, size);
        source.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        source.draw(canvas);
        source.clearColorFilter();

        int[] src = new int[size * size];
        int[] dst = new int[size * size];
        rendered.getPixels(src, 0, size, 0, 0, size, size);

        final int alphaThreshold = 24;
        final int radius = 3;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = y * size + x;
                int alpha = (src[index] >>> 24) & 0xFF;
                if (alpha <= alphaThreshold) {
                    continue;
                }

                boolean edge = false;
                for (int dy = -radius; dy <= radius && !edge; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                            edge = true;
                            break;
                        }
                        int neighborAlpha = (src[ny * size + nx] >>> 24) & 0xFF;
                        if (neighborAlpha <= alphaThreshold) {
                            edge = true;
                            break;
                        }
                    }
                }

                if (edge) {
                    dst[index] = (alpha << 24) | 0x00FFFFFF;
                }
            }
        }

        Bitmap outlined = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        outlined.setPixels(dst, 0, size, 0, 0, size, size);
        rendered.recycle();
        return new BitmapDrawable(context.getResources(), outlined);
    }

    private final RequestListener<Drawable> mErrorListener = new RequestListener<Drawable>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
