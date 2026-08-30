package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.youtube;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.complexcardview.ComplexImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deliberately simple TV card that visually follows the current YouTube TV home feed:
 * rounded 16:9 thumbnail, focus ring around the thumbnail only and metadata below it.
 *
 * This view intentionally does not inherit from Leanback ImageCardView. That prevents the
 * default Leanback info-area selection background from leaking into the title/metadata block.
 */
public class YouTubeVideoCardView extends LinearLayout {
    private static final String META_DELIMITER = "•";

    private FrameLayout mThumbnailShell;
    private FrameLayout mThumbnailClip;
    private ComplexImageView mImageWrapper;
    private ImageView mMainImage;
    private TextView mTitle;
    private TextView mAuthor;
    private LinearLayout mMetadataRow;
    private TextView mChipOne;
    private TextView mChipTwo;
    private TextView mMetadata;
    private TextView mDuration;

    private int mCardWidth;
    private int mCardHeight;
    private boolean mBadgeEnabled = true;
    private boolean mContentAllowed = true;
    private boolean mPreviewEnabled;
    private boolean mFocusedOrSelected;
    private String mBoundVideoId;

    public YouTubeVideoCardView(Context context) {
        super(context);
        init();
    }

    public YouTubeVideoCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public YouTubeVideoCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setBackgroundColor(Color.TRANSPARENT);

        // No YouTube card shadow or lift animation: selection is represented only by the white
        // ring around the thumbnail. Leanback/platform elevation must not leak into metadata.
        if (Build.VERSION.SDK_INT >= 21) {
            setElevation(0f);
            setTranslationZ(0f);
            setStateListAnimator(null);
        }

        LayoutInflater.from(getContext()).inflate(R.layout.youtube_video_card_view, this, true);

        mThumbnailShell = findViewById(R.id.yt_thumbnail_shell);
        mThumbnailClip = findViewById(R.id.yt_thumbnail_clip);
        mImageWrapper = findViewById(R.id.main_image_wrapper);
        mMainImage = findViewById(R.id.main_image);
        mTitle = findViewById(R.id.title_text);
        mAuthor = findViewById(R.id.yt_author_text);
        mMetadataRow = findViewById(R.id.yt_metadata_row);
        mChipOne = findViewById(R.id.yt_chip_one);
        mChipTwo = findViewById(R.id.yt_chip_two);
        mMetadata = findViewById(R.id.content_text);
        mDuration = findViewById(R.id.yt_duration_text);

        if (Build.VERSION.SDK_INT >= 21) {
            mThumbnailClip.setClipToOutline(true);
            mThumbnailShell.setElevation(0f);
            mThumbnailShell.setTranslationZ(0f);
            mThumbnailShell.setStateListAnimator(null);
            mThumbnailClip.setElevation(0f);
            mThumbnailClip.setTranslationZ(0f);
            mThumbnailClip.setStateListAnimator(null);
        }

        updateFocusVisual(false);
    }

    public ImageView getMainImageView() {
        return mMainImage;
    }

    public void setMainImageDimensions(int width, int height) {
        mCardWidth = width;
        mCardHeight = height;

        ViewGroup.LayoutParams shellLp = mThumbnailShell.getLayoutParams();
        shellLp.width = width;
        shellLp.height = height;
        mThumbnailShell.setLayoutParams(shellLp);

        ViewGroup.LayoutParams clipLp = mThumbnailClip.getLayoutParams();
        clipLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        clipLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        mThumbnailClip.setLayoutParams(clipLp);

        ViewGroup.LayoutParams wrapperLp = mImageWrapper.getLayoutParams();
        wrapperLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        wrapperLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        mImageWrapper.setLayoutParams(wrapperLp);

        ViewGroup.LayoutParams imageLp = mMainImage.getLayoutParams();
        imageLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        imageLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        mMainImage.setLayoutParams(imageLp);
        mMainImage.setScaleType(ImageView.ScaleType.CENTER_CROP);

        mImageWrapper.setMainImageDimensions(width, height);
    }

    public void bindVideoId(String videoId) {
        mBoundVideoId = videoId;
    }

    public boolean isBoundTo(String videoId) {
        return TextUtils.equals(mBoundVideoId, videoId);
    }

    public void setTitleText(CharSequence title) {
        mTitle.setText(title);
    }

    public void setTitleVisible(boolean visible) {
        mTitle.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setContentVisible(boolean visible) {
        mContentAllowed = visible;
        if (!visible) {
            mAuthor.setVisibility(GONE);
            mMetadataRow.setVisibility(GONE);
        }
    }

    /**
     * Splits SmartTube's existing secondary title into the same visual hierarchy used by
     * YouTube TV: channel on its own row, compact non-resolution chips and views/date after it.
     *
     * 2K/4K/8K are NOT trusted from browse text. Only qualityHint from
     * YouTubeQualityResolver may create a resolution chip.
     */
    public void setMetadata(CharSequence secondTitle, String author) {
        setMetadata(secondTitle, author, null);
    }

    public void setMetadata(CharSequence secondTitle, String author, String qualityHint) {
        String source = secondTitle != null ? secondTitle.toString().trim() : "";
        String cleanAuthor = author != null ? author.trim() : "";

        List<String> chips = new ArrayList<>();
        List<String> metadataParts = new ArrayList<>();

        if (!TextUtils.isEmpty(source)) {
            String[] parts = source.split("\\s*" + META_DELIMITER + "\\s*");

            for (String raw : parts) {
                String part = raw != null ? raw.trim() : "";

                if (TextUtils.isEmpty(part)) {
                    continue;
                }

                // Never trust a 2K/4K/8K/HD token from feed metadata.
                if (isResolutionChip(part)) {
                    continue;
                }

                if (isAuxiliaryChip(part)) {
                    if (chips.size() < 2) {
                        chips.add(normalizeAuxiliaryChip(part));
                    }
                    continue;
                }

                // Strip leading chip-like tokens from metadata such as
                // "8K 28K views", while keeping "28K views".
                String[] words = part.split("\\s+", 2);

                if (words.length > 1 && isResolutionChip(words[0])) {
                    part = words[1].trim();

                    if (isAuxiliaryChip(part)) {
                        if (chips.size() < 2) {
                            chips.add(normalizeAuxiliaryChip(part));
                        }
                        continue;
                    }
                } else if (words.length > 1 && isAuxiliaryChip(words[0])) {
                    if (chips.size() < 2) {
                        chips.add(normalizeAuxiliaryChip(words[0]));
                    }
                    part = words[1].trim();
                }

                if (!TextUtils.isEmpty(cleanAuthor) && part.equalsIgnoreCase(cleanAuthor)) {
                    continue;
                }

                if (!TextUtils.isEmpty(part)) {
                    metadataParts.add(part);
                }
            }
        }

        if (TextUtils.isEmpty(cleanAuthor) && !metadataParts.isEmpty()) {
            cleanAuthor = metadataParts.remove(0);
        }

        // The ONLY source of 2K/4K/8K.
        String realResolution = normalizeResolutionChip(qualityHint);

        if (!TextUtils.isEmpty(realResolution)) {
            chips.add(0, realResolution);

            while (chips.size() > 2) {
                chips.remove(chips.size() - 1);
            }
        }

        mAuthor.setText(cleanAuthor);
        mAuthor.setVisibility(TextUtils.isEmpty(cleanAuthor) ? GONE : VISIBLE);

        bindChip(mChipOne, chips.size() > 0 ? chips.get(0) : null);
        bindChip(mChipTwo, chips.size() > 1 ? chips.get(1) : null);

        String metadata = joinMetadata(metadataParts);
        mMetadata.setText(metadata);
        mMetadata.setVisibility(TextUtils.isEmpty(metadata) ? GONE : VISIBLE);

        boolean hasMetadata = mChipOne.getVisibility() == VISIBLE ||
                mChipTwo.getVisibility() == VISIBLE || mMetadata.getVisibility() == VISIBLE;

        mMetadataRow.setVisibility(mContentAllowed && hasMetadata ? VISIBLE : GONE);

        if (!mContentAllowed) {
            mAuthor.setVisibility(GONE);
        }
    }

    private void bindChip(TextView chip, String text) {
        if (TextUtils.isEmpty(text)) {
            chip.setText(null);
            chip.setVisibility(GONE);
        } else {
            chip.setText(text);
            chip.setVisibility(VISIBLE);
        }
    }

    private boolean isResolutionChip(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }

        String value = text.toUpperCase(Locale.US).replace(" ", "");

        return value.equals("8K") ||
                value.equals("4320P") ||
                value.equals("7680X4320") ||
                value.equals("4K") ||
                value.equals("2160P") ||
                value.equals("3840X2160") ||
                value.equals("UHD") ||
                value.equals("2K") ||
                value.equals("1440P") ||
                value.equals("2560X1440") ||
                value.equals("QHD") ||
                value.equals("HD") ||
                value.equals("1080P");
    }

    private boolean isAuxiliaryChip(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }

        String value = text.toUpperCase(Locale.US).replace(" ", "");

        return value.equals("HDR") ||
                value.equals("CC") ||
                value.equals("60FPS");
    }

    private String normalizeAuxiliaryChip(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }

        return text.toUpperCase(Locale.US).replace(" ", "");
    }

    private String normalizeResolutionChip(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }

        String value = text.toUpperCase(Locale.US).replace(" ", "");

        if (value.equals("8K") ||
                value.equals("4320P") ||
                value.equals("7680X4320")) {
            return "8K";
        }

        if (value.equals("4K") ||
                value.equals("2160P") ||
                value.equals("3840X2160") ||
                value.equals("UHD")) {
            return "4K";
        }

        if (value.equals("2K") ||
                value.equals("1440P") ||
                value.equals("2560X1440") ||
                value.equals("QHD")) {
            return "2K";
        }

        return null;
    }

    private String joinMetadata(List<String> parts) {
        if (parts.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (String part : parts) {
            if (result.length() > 0) {
                result.append(" ").append(META_DELIMITER).append(" ");
            }

            result.append(part);
        }

        return result.toString();
    }

    public void setDurationText(CharSequence duration, boolean live) {
        if (mDuration == null) {
            return;
        }

        if (TextUtils.isEmpty(duration)) {
            mDuration.setText(null);
            mDuration.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            mDuration.setVisibility(GONE);
            return;
        }

        mDuration.setText(duration);
        mDuration.setBackgroundResource(live ? R.drawable.yt_live_badge_bg : R.drawable.yt_duration_badge_bg);
        mDuration.setCompoundDrawablesWithIntrinsicBounds(live ? R.drawable.yt_live_broadcast : 0, 0, 0, 0);
        mDuration.setCompoundDrawablePadding(live ? dp(3) : 0);
        mDuration.setPadding(dp(live ? 7 : 6), dp(3), dp(live ? 8 : 6), dp(3));
        mDuration.setVisibility(VISIBLE);
    }

    public void setProgress(int percent) {
        mImageWrapper.setProgress(percent);
    }

    public void enableBadge(boolean enabled) {
        mBadgeEnabled = enabled;
        if (!enabled) {
            mImageWrapper.setBadgeText(null);
        }
    }

    public void setBadgeText(String text) {
        if (mBadgeEnabled) {
            mImageWrapper.setBadgeText(text);
        }
    }

    public void setBadgeColor(int color) {
        mImageWrapper.setBadgeColor(color);
    }

    public void setPreview(Video video) {
        mPreviewEnabled = video != null;
        mImageWrapper.setPreview(video);
    }

    public void setMute(boolean muted) {
        mImageWrapper.setMute(muted);
    }

    public void stopPreview(boolean immediately) {
        if (mPreviewEnabled) {
            mImageWrapper.stopPlayback(immediately);
        }
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        updateFocusVisual(selected || hasFocus());
        updatePreview(selected || hasFocus());
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        updateFocusVisual(gainFocus || isSelected());
        updatePreview(gainFocus || isSelected());
    }

    private void updatePreview(boolean active) {
        if (!mPreviewEnabled) {
            return;
        }

        if (active) {
            mImageWrapper.startPlayback();
        } else {
            mImageWrapper.stopPlayback();
        }
    }

    private void updateFocusVisual(boolean focused) {
        if (mFocusedOrSelected == focused && mThumbnailShell.getBackground() != null) {
            return;
        }

        mFocusedOrSelected = focused;

        int stroke = dp(3);
        int radius = dp(12);
        GradientDrawable shell = new GradientDrawable();
        shell.setShape(GradientDrawable.RECTANGLE);
        shell.setColor(Color.TRANSPARENT);
        shell.setCornerRadius(radius);
        shell.setStroke(stroke, focused ? Color.WHITE : Color.TRANSPARENT);
        mThumbnailShell.setBackground(shell);
        mThumbnailShell.setPadding(stroke, stroke, stroke, stroke);

        GradientDrawable clip = new GradientDrawable();
        clip.setShape(GradientDrawable.RECTANGLE);
        clip.setColor(Color.BLACK);
        clip.setCornerRadius(Math.max(0, radius - stroke));
        mThumbnailClip.setBackground(clip);

        // YouTube TV makes the focused video's title bright white while neighbouring titles
        // are slightly subdued. This gives directional focus feedback without adding a card block.
        if (mTitle != null) {
            mTitle.setTextColor(focused ? Color.WHITE : Color.rgb(184, 184, 184));
        }

        // Keep the card dimensions completely stable; the white ring is the focus indicator.
        animate().cancel();
        setScaleX(1.0f);
        setScaleY(1.0f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPreview(true);
        super.onDetachedFromWindow();
    }
}
