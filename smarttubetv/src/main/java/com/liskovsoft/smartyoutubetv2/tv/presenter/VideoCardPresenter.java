package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build.VERSION;
import android.util.Pair;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.LongClickPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.youtube.YouTubeQualityResolver;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.youtube.YouTubeVideoCardView;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * Video presenter rebuilt from the upstream SmartTube presenter, with only the visual card view
 * replaced. Playback, item click behaviour, preview, data loading and the existing model all stay
 * on SmartTube's original code path.
 */
public class VideoCardPresenter extends LongClickPresenter {
    private static final String TAG = VideoCardPresenter.class.getSimpleName();
    private int mCardPreviewType;
    private int mThumbQuality;
    private int mWidth;
    private int mHeight;

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();

        mCardPreviewType = getCardPreviewType(context);
        mThumbQuality = getThumbQuality(context);
        updateDimensions(context);

        YouTubeVideoCardView cardView = new YouTubeVideoCardView(context);
        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);
        cardView.enableBadge(isBadgeEnabled());
        cardView.setTitleVisible(isTitleEnabled());
        cardView.setContentVisible(isContentEnabled());
        cardView.setMainImageDimensions(mWidth, mHeight);

        return new ViewHolder(cardView);
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        super.onBindViewHolder(viewHolder, item);

        Video video = (Video) item;
        YouTubeVideoCardView cardView = (YouTubeVideoCardView) viewHolder.view;
        Context context = cardView.getContext();

        final String boundVideoId = video.videoId;
        final CharSequence boundSecondTitle = video.getSecondTitle();
        final String boundAuthor = video.getAuthor();

        cardView.bindVideoId(boundVideoId);
        cardView.setTitleText(video.getTitle());

        // Never guess 2K/4K/8K from the title, badge or browse metadata.
        // The resolution chip is added only after MediaServiceCore returns
        // the actual available stream formats for this video.
        cardView.setMetadata(boundSecondTitle, boundAuthor, null);

        YouTubeQualityResolver.resolve(video, quality ->
                cardView.post(() -> {
                    // Leanback can recycle this view while the format request
                    // is still running. Do not put a result on the wrong card.
                    if (cardView.isBoundTo(boundVideoId)) {
                        cardView.setMetadata(
                                boundSecondTitle,
                                boundAuthor,
                                quality
                        );
                    }
                }));

        cardView.setTitleVisible(isTitleEnabled());
        cardView.setContentVisible(isContentEnabled());

        // Count progress that is very close to zero. E.g. when the user closed a video immediately.
        cardView.setProgress(video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched));
        // YouTube TV-style thumbnail badges: dark duration pill for normal videos and
        // a red broadcast LIVE pill in the same bottom-right position for live streams.
        boolean showDuration = !video.hasNewContent && !video.isLive && !video.isUpcoming && !video.isShorts &&
                video.badge != null && video.badge.length() > 0;
        boolean showLive = video.isLive;
        cardView.setDurationText(
                showLive ? context.getString(R.string.badge_live) : (showDuration ? video.badge : null),
                showLive
        );
        cardView.setBadgeText(
                showDuration || showLive ? null :
                video.hasNewContent ? context.getString(R.string.badge_new_content) :
                video.isShorts ? context.getString(R.string.header_shorts).toUpperCase() :
                video.badge
        );
        cardView.setBadgeColor(video.hasNewContent || video.isUpcoming ?
                ContextCompat.getColor(context, R.color.dark_red) : ContextCompat.getColor(context, R.color.black));

        if (mCardPreviewType != MainUIData.CARD_PREVIEW_DISABLED) {
            cardView.setPreview(video);
            cardView.setMute(mCardPreviewType == MainUIData.CARD_PREVIEW_MUTED);
        }

        // Grid scale can change from settings, so apply current dimensions on each bind as upstream does.
        updateDimensions(context);
        cardView.setMainImageDimensions(mWidth, mHeight);

        if (context instanceof Activity && ((Activity) context).isDestroyed()) {
            return;
        }

        Glide.with(context)
                .load(ClickbaitRemover.updateThumbnail(video, mThumbQuality))
                .apply(ViewUtil.glideOptions())
                .override(mWidth, mHeight)
                .diskCacheStrategy(VERSION.SDK_INT > 21 ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE)
                .listener(mErrorListener)
                .error(
                        Glide.with(context)
                                .load(video.cardImageUrl)
                                .apply(ViewUtil.glideOptions())
                                .listener(mErrorListener)
                                .error(R.drawable.card_placeholder)
                )
                .into(cardView.getMainImageView());
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        super.onUnbindViewHolder(viewHolder);

        YouTubeVideoCardView cardView = (YouTubeVideoCardView) viewHolder.view;
        cardView.bindVideoId(null);
        cardView.stopPreview(true);
        Glide.with(cardView.getContext().getApplicationContext()).clear(cardView.getMainImageView());
        cardView.getMainImageView().setImageDrawable(null);
    }

    private void updateDimensions(Context context) {
        Pair<Integer, Integer> dimens = getCardDimensPx(context);
        mWidth = dimens.first;
        mHeight = dimens.second;
    }

    protected Pair<Integer, Integer> getCardDimensPx(Context context) {
        return GridFragmentHelper.getCardDimensPx(context, R.dimen.card_width, R.dimen.card_height,
                MainUIData.instance(context).getVideoGridScale());
    }

    // Kept for compatibility with TinyCardPresenter and future SmartTube subclasses.
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
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target,
                                    boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                       DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
