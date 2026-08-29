package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build.VERSION;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Presenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.googlecommon.common.helpers.ServiceHelper;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.LongClickPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.complexcardview.ComplexImageCardView;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

/*
 * A CardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an Image CardView
 */
public class ShortsCardPresenter extends LongClickPresenter {
    private static final String TAG = ShortsCardPresenter.class.getSimpleName();
    private int mDefaultBackgroundColor = -1;
    private int mDefaultTextColor = -1;
    private int mSelectedBackgroundColor = -1;
    private int mSelectedTextColor = -1;
    private int mCardPreviewType;
    private int mThumbQuality;
    private int mWidth;
    private int mHeight;

    // Local id used only for the extra Shorts metadata row. Keeping it outside R avoids
    // touching global resources/layouts and therefore keeps Search/Home styling unchanged.
    private static final int SHORTS_METADATA_VIEW_ID = 0x1f0a3901;

    private static final class ShortsViewHolder extends Presenter.ViewHolder {
        private Disposable metadataAction;
        private String boundVideoId;

        private ShortsViewHolder(ComplexImageCardView view) {
            super(view);
        }

        private void disposeMetadata() {
            if (metadataAction != null && !metadataAction.isDisposed()) {
                metadataAction.dispose();
            }
            metadataAction = null;
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();

        mDefaultBackgroundColor =
            ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardDefaultBackground));
        mDefaultTextColor =
                ContextCompat.getColor(context, R.color.card_default_text);
        mSelectedBackgroundColor =
                ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardSelectedBackground));
        mSelectedTextColor =
                ContextCompat.getColor(context, R.color.card_selected_text_grey);

        mCardPreviewType = getCardPreviewType(context);
        mThumbQuality = getThumbQuality(context);

        boolean isCardMultilineTitleEnabled = isCardMultilineTitleEnabled(context);
        boolean isCardMultilineSubtitleEnabled = isCardMultilineSubtitleEnabled(context);
        boolean isCardTextAutoScrollEnabled = isCardTextAutoScrollEnabled(context);
        float cardTextScrollSpeed = getCardTextScrollSpeed(context);

        updateDimensions(context);

        ComplexImageCardView cardView = new ComplexImageCardView(context) {
            @Override
            public void setSelected(boolean selected) {
                updateCardBackgroundColor(this, selected);
                super.setSelected(selected);
            }
        };

        cardView.setTitleLinesNum(isCardMultilineTitleEnabled ? 2 : 1);
        // Shorts have three semantic rows: title, creator, then views + publish age.
        // The creator itself must never consume a second row with an @handle.
        cardView.setContentLinesNum(1);
        ensureShortsMetadataView(cardView);
        cardView.enableTextAutoScroll(isCardTextAutoScrollEnabled);
        cardView.setTextScrollSpeed(cardTextScrollSpeed);
        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);
        cardView.enableBadge(isBadgeEnabled());
        cardView.enableTitle(isTitleEnabled());
        cardView.enableContent(isContentEnabled());
        cardView.setBackgroundColor(mDefaultBackgroundColor); // background is temporarily visible during animations
        //if (VERSION.SDK_INT >= 23 && MainUIData.instance(context).isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS)) {
        //    cardView.setForeground(ContextCompat.getDrawable(context, R.drawable.lb_card_outline));
        //}
        updateCardBackgroundColor(cardView, false);
        return new ShortsViewHolder(cardView);
    }

    private void updateCardBackgroundColor(ComplexImageCardView view, boolean selected) {
        int backgroundColor = selected ? mSelectedBackgroundColor : mDefaultBackgroundColor;
        int textColor = selected ? mSelectedTextColor : mDefaultTextColor;

        // Both background colors should be set because the view's
        // background is temporarily visible during animations.
        // NOTE: has visual bug with rounded corners
        //view.setBackgroundColor(backgroundColor);

        View infoField = view.findViewById(R.id.info_field);
        if (infoField != null) {
            infoField.setBackgroundColor(backgroundColor);
        }

        TextView titleText = view.findViewById(R.id.title_text);
        if (titleText != null) {
            titleText.setTextColor(textColor);
        }
        TextView contentText = view.findViewById(R.id.content_text);
        if (contentText != null) {
            contentText.setTextColor(selected ? 0xFF5F5F5F : 0xFFB8B8B8);
        }
        TextView metadataText = view.findViewById(SHORTS_METADATA_VIEW_ID);
        if (metadataText != null) {
            metadataText.setTextColor(selected ? 0xFF6A6A6A : 0xFFA9A9A9);
        }
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        super.onBindViewHolder(viewHolder, item);

        Video video = (Video) item;

        ComplexImageCardView cardView = (ComplexImageCardView) viewHolder.view;
        Context context = cardView.getContext();
        ShortsViewHolder holder = (ShortsViewHolder) viewHolder;

        holder.disposeMetadata();
        holder.boundVideoId = video.videoId;

        cardView.setTitleText(video.getTitle());

        // Exact YouTube TV hierarchy:
        //   video title
        //   creator display name
        //   views • publish age
        // Never show the duplicated @handle next to the creator.
        String creator = extractCreator(video.getAuthor(), video.getSecondTitle(), video.getSecondTitleFull());
        cardView.setContentText(creator);

        TextView metadataText = ensureShortsMetadataView(cardView);
        String inlineMetadata = extractViewsAndAge(video.getSecondTitleFull(), video.getSecondTitle());
        setShortsMetadataText(metadataText, inlineMetadata);
        enrichShortsMetadata(holder, video, cardView, metadataText);
        // Count progress that very close to zero. E.g. when user closed video immediately.
        cardView.setProgress(video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched));
        // Keep SmartTube's original Shorts card/layout. Only replace the old SHORTS overlay
        // with a duration badge. Prefer the badge text already provided by the feed, and fall
        // back to SmartTube's own durationMs when the Shorts response omitted that text.
        String duration = video.badge;
        if (TextUtils.isEmpty(duration) && video.getDurationMs() > 0) {
            duration = ServiceHelper.millisToTimeText(video.getDurationMs());
        }
        cardView.setBadgeText(duration);
        styleDurationBadge(cardView, duration);

        if (mCardPreviewType != MainUIData.CARD_PREVIEW_DISABLED) {
            cardView.setPreview(video);
            cardView.setMute(mCardPreviewType == MainUIData.CARD_PREVIEW_MUTED);
        }

        cardView.setMainImageDimensions(mWidth, mHeight);

        if (context instanceof Activity && ((Activity) context).isDestroyed()) {
            // Glide.with(context): IllegalArgumentException: You cannot start a load for a destroyed activity
            return;
        }

        Glide.with(context)
                //.asBitmap() // disable animation (webp, gif)
                .load(ClickbaitRemover.updateThumbnail(video, mThumbQuality))
                //.placeholder(mDefaultCardImage)
                .apply(ViewUtil.glideOptions())
                // improve image compression on low end devices
                .override(mWidth, mHeight)
                // com.liskovsoft.smartyoutubetv2.tv.util.CacheGlideModule
                // Cache makes app crashing on old android versions
                .diskCacheStrategy(VERSION.SDK_INT > 21 ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE)
                .listener(mErrorListener)
                .error(
                    // Updated thumbnail url not found
                    Glide.with(context)
                        .load(video.cardImageUrl) // always working
                        //.placeholder(mDefaultCardImage)
                        .apply(ViewUtil.glideOptions())
                        .listener(mErrorListener)
                        .error(R.drawable.card_placeholder) // R.color.lb_grey
                )
                .into(cardView.getMainImageView());
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        super.onUnbindViewHolder(viewHolder);

        ComplexImageCardView cardView = (ComplexImageCardView) viewHolder.view;
        ShortsViewHolder holder = (ShortsViewHolder) viewHolder;
        holder.disposeMetadata();
        holder.boundVideoId = null;
        setShortsMetadataText(cardView.findViewById(SHORTS_METADATA_VIEW_ID), null);

        // Remove references to images so that the garbage collector can free up memory.
        cardView.setBadgeImage(null);
        cardView.setMainImage(null);

        // Cleanup Glide resources. https://chatgpt.com/share/682120c5-e428-8010-b848-371b2dec0cd5
        Glide.with(cardView.getContext().getApplicationContext()).clear(cardView.getMainImageView());
    }

    private TextView ensureShortsMetadataView(ComplexImageCardView cardView) {
        TextView existing = cardView.findViewById(SHORTS_METADATA_VIEW_ID);
        if (existing != null) {
            return existing;
        }

        View info = cardView.findViewById(R.id.info_field);
        if (!(info instanceof ViewGroup)) {
            return null;
        }

        TextView metadata = new TextView(cardView.getContext());
        metadata.setId(SHORTS_METADATA_VIEW_ID);
        metadata.setSingleLine(true);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        metadata.setIncludeFontPadding(false);
        metadata.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        metadata.setTextColor(0xFFA9A9A9);
        metadata.setVisibility(View.GONE);

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.BELOW, R.id.content_text);
        params.addRule(RelativeLayout.ALIGN_START, R.id.content_text);
        params.addRule(RelativeLayout.ALIGN_END, R.id.content_text);
        params.topMargin = dp(cardView.getContext(), 4);
        metadata.setLayoutParams(params);

        ((ViewGroup) info).addView(metadata);
        return metadata;
    }

    private void setShortsMetadataText(TextView view, String text) {
        if (view == null) {
            return;
        }
        String value = text != null ? text.trim() : "";
        view.setText(value);
        view.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
    }

    private String extractCreator(CharSequence... candidates) {
        for (CharSequence candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String[] parts = candidate.toString().split("\\s*[•·]\\s*");
            for (String raw : parts) {
                String part = raw != null ? raw.trim() : "";
                if (TextUtils.isEmpty(part) || part.startsWith("@") || looksLikeViews(part) || looksLikeAge(part)) {
                    continue;
                }
                return part;
            }
        }
        return "";
    }

    private String extractViewsAndAge(CharSequence... candidates) {
        String views = null;
        String age = null;

        for (CharSequence candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String[] parts = candidate.toString().split("\\s*[•·]\\s*");
            for (String raw : parts) {
                String part = raw != null ? raw.trim() : "";
                if (TextUtils.isEmpty(part) || part.startsWith("@")) {
                    continue;
                }
                if (views == null && looksLikeViews(part)) {
                    views = part;
                } else if (age == null && looksLikeAge(part)) {
                    age = part;
                }
            }
        }

        return joinViewsAndAge(views, age);
    }

    private String joinViewsAndAge(String views, String age) {
        if (TextUtils.isEmpty(views)) {
            return TextUtils.isEmpty(age) ? "" : age;
        }
        if (TextUtils.isEmpty(age)) {
            return views;
        }
        return views + " • " + age;
    }

    private boolean looksLikeViews(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String value = text.toLowerCase();
        return value.contains(" view") || value.endsWith("views") || value.contains("weergav") ||
                value.contains("kijkers") || value.contains("watching") || value.contains("visualizaciones");
    }

    private boolean looksLikeAge(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String value = text.toLowerCase();
        return value.contains(" ago") || value.contains("geleden") || value.contains(" geleden") ||
                value.contains("hour") || value.contains("day") || value.contains("week") ||
                value.contains("month") || value.contains("year") || value.contains("uur") ||
                value.contains("dag") || value.contains("week") || value.contains("maand") ||
                value.contains("jaar");
    }

    private void enrichShortsMetadata(ShortsViewHolder holder, Video video,
                                      ComplexImageCardView cardView, TextView metadataText) {
        if (TextUtils.isEmpty(video.videoId)) {
            return;
        }

        MediaItemService itemService = YouTubeServiceManager.instance().getMediaItemService();
        Observable<MediaItemMetadata> metadataObserve = video.mediaItem != null ?
                itemService.getMetadataObserve(video.mediaItem) :
                itemService.getMetadataObserve(video.videoId, video.getPlaylistId(),
                        video.playlistIndex, video.playlistParams);

        holder.metadataAction = metadataObserve.subscribe(
                metadata -> {
                    if (metadata == null || !TextUtils.equals(video.videoId, holder.boundVideoId)) {
                        return;
                    }

                    cardView.post(() -> {
                        if (!TextUtils.equals(video.videoId, holder.boundVideoId)) {
                            return;
                        }

                        String creator = extractCreator(metadata.getAuthor(), video.getAuthor(),
                                video.getSecondTitle(), video.getSecondTitleFull());
                        if (!TextUtils.isEmpty(creator)) {
                            cardView.setContentText(creator);
                        }

                        String views = metadata.getViewCount();
                        String age = metadata.getPublishedDate();
                        String richMetadata = joinViewsAndAge(views, age);

                        if (TextUtils.isEmpty(richMetadata)) {
                            video.sync(metadata);
                            richMetadata = extractViewsAndAge(video.getSecondTitleFull(), video.getSecondTitle());
                        }

                        setShortsMetadataText(metadataText, richMetadata);
                    });
                },
                error -> Log.e(TAG, "Shorts metadata load failed: " + error.getMessage())
        );
    }

    private void styleDurationBadge(ComplexImageCardView cardView, String duration) {
        TextView badge = cardView.findViewById(R.id.extra_text_badge);
        if (badge == null) {
            return;
        }

        if (TextUtils.isEmpty(duration)) {
            badge.setVisibility(View.INVISIBLE);
            return;
        }

        int horizontal = dp(cardView.getContext(), 6);
        int vertical = dp(cardView.getContext(), 3);

        badge.setBackgroundResource(R.drawable.yt_duration_badge_bg);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setIncludeFontPadding(false);
        badge.setPadding(horizontal, vertical, horizontal, vertical);
        badge.setVisibility(View.VISIBLE);
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void updateDimensions(Context context) {
        Pair<Integer, Integer> dimens = getCardDimensPx(context);

        mWidth = dimens.first;
        mHeight = dimens.second;
    }
    
    protected Pair<Integer, Integer> getCardDimensPx(Context context) {
        return GridFragmentHelper.getCardDimensPx(context, R.dimen.shorts_card_width, R.dimen.shorts_card_height, MainUIData.instance(context).getVideoGridScale());
    }

    protected boolean isCardTextAutoScrollEnabled(Context context) {
        return MainUIData.instance(context).isCardTextAutoScrollEnabled();
    }

    protected int getCardPreviewType(Context context) {
        return MainUIData.instance(context).getCardPreviewType();
    }

    protected boolean isCardMultilineTitleEnabled(Context context) {
        return MainUIData.instance(context).isCardMultilineTitleEnabled();
    }

    protected boolean isCardMultilineSubtitleEnabled(Context context) {
        return MainUIData.instance(context).isCardMultilineSubtitleEnabled();
    }

    protected float getCardTextScrollSpeed(Context context) {
        return MainUIData.instance(context).getCardTextScrollSpeed();
    }

    protected int getThumbQuality(Context context) {
        return MainUIData.instance(context).getThumbQuality();
    }

    protected boolean isContentEnabled() {
        return true;
    }

    protected boolean isTitleEnabled() {
        return true;
    }

    protected boolean isBadgeEnabled() {
        return true;
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

    private final RequestListener<Bitmap> mErrorListener2 = new RequestListener<Bitmap>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
