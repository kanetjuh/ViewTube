package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.browse;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.SearchOrbView;
import androidx.leanback.widget.SearchOrbView.Colors;
import androidx.leanback.widget.TitleView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.locale.LocaleUtility;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.LanguageSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.AccountChangeListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tooltips.TooltipCompatHandler;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.search.LongClickSearchOrbView;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.time.DateTimeView;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.util.Locale;

import static androidx.leanback.widget.TitleViewAdapter.BRANDING_VIEW_VISIBLE;
import static androidx.leanback.widget.TitleViewAdapter.FULL_VIEW_VISIBLE;
import static androidx.leanback.widget.TitleViewAdapter.SEARCH_VIEW_VISIBLE;

/**
 * View that supports dpad navigation between children<br/>
 * NOTE: You should set android:nextFocusLeft and android:nextFocusRight<br/>
 * https://stackoverflow.com/questions/38169378/use-multiple-orb-buttons-or-other-buttons-in-the-leanbacks-title-view<br/>
 * https://stackoverflow.com/questions/40802470/add-button-to-browsefragment
 */
public class NavigateTitleView extends TitleView implements OnDataChange, AccountChangeListener {
    private LongClickSearchOrbView mAccountView;
    private SearchOrbView mLanguageView;
    private SearchOrbView mExitPip;
    private TextView mPipTitle;
    private int mSearchVisibility = View.INVISIBLE;
    private int mBrandingVisibility = View.INVISIBLE;
    private DateTimeView mGlobalClock;
    private DateTimeView mGlobalDate;
    private SearchOrbView mSearchOrbView;
    private TextView mSearchPill;
    private TextView mAccountLabel;
    private boolean mInitDone;
    private int mFlags = FULL_VIEW_VISIBLE;
    private int mIconWidth;
    private int mIconHeight;
    private boolean mIsSearchOrbEnabled;
    private boolean mIsAccountViewEnabled;
    private boolean mIsLanguageViewEnabled;
    private boolean mIsGlobalClockEnabled;

    public NavigateTitleView(Context context) {
        super(context);
    }

    public NavigateTitleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NavigateTitleView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // findViewById is null in constructor. Init mAccountView later.
    }

    @Override
    public View focusSearch(View focused, int direction) {
        View nextFoundFocusableViewInLayout = null;

        // Only concerned about focusing left and right at the moment
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {

            // Try to find the next focusable item in this layout for the supplied direction
            int nextFoundFocusableViewInLayoutId = -1;
            switch(direction) {
                case View.FOCUS_LEFT :
                    nextFoundFocusableViewInLayoutId = focused.getNextFocusLeftId();
                    break;
                case View.FOCUS_RIGHT :
                    nextFoundFocusableViewInLayoutId = focused.getNextFocusRightId();
                    break;
            }

            // View id for next focus direction found....get the View
            if (nextFoundFocusableViewInLayoutId != -1) {
                nextFoundFocusableViewInLayout = findViewById(nextFoundFocusableViewInLayoutId);
            }
        }

        //  Return the found View in the layout if it's focusable
        if (nextFoundFocusableViewInLayout != null && nextFoundFocusableViewInLayout != focused && nextFoundFocusableViewInLayout.isFocusable()) {
            if (nextFoundFocusableViewInLayout.getVisibility() == View.VISIBLE) {
                return nextFoundFocusableViewInLayout;
            } else {
                return focusSearch(nextFoundFocusableViewInLayout, direction);
            }
        } else {
            // No focusable view found in layout...propagate to super (should invoke the BrowseFrameLayout.OnFocusSearchListener
            return super.focusSearch(focused, direction);
        }
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // The modern YouTube-like search pill is the most natural first focus target.
        return (mSearchPill != null && mSearchPill.getVisibility() == View.VISIBLE && mSearchPill.requestFocus()) ||
                getSearchAffordanceView().requestFocus() ||
                super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }

    @Override
    public void updateComponentsVisibility(int flags) {
        // Fix for: Fatal Exception: java.lang.IllegalStateException
        // Fragment has not been attached yet.
        // Inside: super.updateComponentsVisibility(flags);
        if (getWindowToken() == null) {
            return;
        }

        super.updateComponentsVisibility(flags);

        init();

        mFlags = flags;

        mSearchVisibility = (flags & SEARCH_VIEW_VISIBLE) == SEARCH_VIEW_VISIBLE
                ? View.VISIBLE : View.INVISIBLE;

        mBrandingVisibility = (flags & BRANDING_VIEW_VISIBLE) == BRANDING_VIEW_VISIBLE
                ? View.VISIBLE : View.INVISIBLE;

        // Keep the YouTube-style account + mic + search pill visible whenever the title itself is shown.
        mSearchOrbView.setVisibility(mSearchVisibility);
        mAccountView.setVisibility(mSearchVisibility);

        // v22: keep account label visibility tied to Leanback title visibility.
        // BrowseFragment controls the label width: width > 0 means the rail is expanded.
        // When Leanback hides the title controls while scrolling down, hide the account
        // name as well; when the title comes back, restore it only for an expanded rail.
        if (mAccountLabel != null) {
            // v25: an anonymous session has no channel name. Never resurrect the old
            // "None" label just because the title/rail becomes visible again.
            boolean hasAccountName = mAccountLabel.length() > 0;
            boolean showAccountLabel = hasAccountName &&
                    mSearchVisibility == View.VISIBLE && mAccountLabel.getWidth() > 0;
            mAccountLabel.setVisibility(showAccountLabel ? View.VISIBLE : View.INVISIBLE);
            mAccountLabel.setAlpha(showAccountLabel ? 1f : 0f);
        }

        if (mSearchPill != null) {
            mSearchPill.setVisibility(mSearchVisibility);
        }

        // These controls are deliberately hidden from the Home title bar.
        mLanguageView.setVisibility(View.GONE);

        if (mExitPip != null && PlaybackPresenter.instance(getContext()).isRunningInBackground() && mSearchVisibility == View.VISIBLE) {
            mExitPip.setVisibility(View.VISIBLE);
            mPipTitle.setVisibility(View.VISIBLE);
        } else if (mExitPip != null) {
            mExitPip.setVisibility(View.GONE);
            mPipTitle.setVisibility(View.GONE);
        }

        // The reference UI has branding at the right, not a clock/date block.
        mGlobalClock.setVisibility(View.GONE);
        mGlobalDate.setVisibility(View.GONE);
    }

    private void init() {
        if (mInitDone) {
            return;
        }

        MediaServiceManager.instance().addAccountListener(this);

        setupButtons();

        MainUIData mainUIData = MainUIData.instance(getContext());
        mainUIData.setOnChange(this);

        mInitDone = true;
    }

    private void setupButtons() {
        MainUIData mainUIData = MainUIData.instance(getContext());

        mSearchOrbView = findViewById(R.id.title_orb);
        mSearchPill = findViewById(R.id.yt_search_pill);
        if (mSearchPill != null) {
            mSearchPill.setOnClickListener(v -> mSearchOrbView.performClick());
        }

        mAccountView = findViewById(R.id.account_orb);
        mAccountLabel = findViewById(R.id.yt_account_label);
        mAccountView.setOnOrbClickedListener(v -> AccountSelectionPresenter.instance(getContext()).nextAccountOrDialog());
        mAccountView.setOnOrbLongClickedListener(v -> {
            AccountSettingsPresenter.instance(getContext()).show();
            return true;
        });
        // v26: the expanded sidebar already shows the account name. Disable the stock
        // tooltip bubble and make the focused avatar free to zoom outside its own bounds.
        makeAccountOrbOverflowSafe();
        // v28: Leanback's stock orb/shadow is 52dp and zooms to 120% on focus, while our
        // account artwork is much smaller. Scale ONLY the orb/shadow surface so the focus
        // flash follows the actual avatar instead of drawing a huge halo around it.
        mAccountView.setOrbVisualScale(0.64f);
        // The compact avatar is visually left of Leanback's stock orb centre. Shift only the
        // focus/background circle left; the profile artwork itself stays exactly where it is.
        mAccountView.setOrbVisualOffsetXDp(-6f);

        mLanguageView = findViewById(R.id.language_orb);
        mLanguageView.setOnOrbClickedListener(v -> LanguageSettingsPresenter.instance(getContext()).show());
        TooltipCompatHandler.setTooltipText(mLanguageView, getContext().getString(R.string.settings_language_country));

        mExitPip = findViewById(R.id.exit_pip);
        mPipTitle = findViewById(R.id.pip_title);
        mExitPip.setOnOrbClickedListener(v -> ViewManager.instance(getContext()).startView(PlaybackView.class));
        ViewUtil.enableMarquee(mPipTitle);
        ViewUtil.setTextScrollSpeed(mPipTitle, mainUIData.getCardTextScrollSpeed());
        TooltipCompatHandler.setTooltipText(mExitPip, getContext().getString(R.string.return_to_background_video));

        mGlobalClock = findViewById(R.id.global_time);
        mGlobalClock.showDate(false);

        mGlobalDate = findViewById(R.id.global_date);
        mGlobalDate.showTime(false);
        mGlobalDate.showDate(true);

        updateButtonsVisibility();
    }

    private void updateButtonsVisibility() {
        MainUIData mainUIData = MainUIData.instance(getContext());

        // Home branding is intentionally stable and no longer depends on the old top-button toggles.
        mIsSearchOrbEnabled = true;
        mIsAccountViewEnabled = true;
        mIsLanguageViewEnabled = false;
        mIsGlobalClockEnabled = false;

        mSearchOrbView.setVisibility(View.VISIBLE);
        mAccountView.setVisibility(View.VISIBLE);
        if (mSearchPill != null) {
            mSearchPill.setVisibility(View.VISIBLE);
        }
        mLanguageView.setVisibility(View.GONE);
        mGlobalClock.setVisibility(View.GONE);
        mGlobalDate.setVisibility(View.GONE);

        Utils.postDelayed(this::updateAccountIcon, 1_000); // give a time to engine to fetch an updated icon url
        //updateAccountIcon();
        updateLanguageIcon();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (visibility == View.VISIBLE) { // scroll grid up, scroll grid down
            applyPipParameters();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (hasWindowFocus) { // pip window closed, dialog closed
            applyPipParameters();
        }
    }

    @Override
    public void onAccountChanged(Account account) {
        updateAccountIcon();
    }

    private void applyPipParameters() {
        if (mExitPip != null) {
            int newVisibility = PlaybackPresenter.instance(getContext()).isRunningInBackground() ? mSearchVisibility : View.INVISIBLE;
            mExitPip.setVisibility(newVisibility);
            mPipTitle.setVisibility(newVisibility);

            if (newVisibility == View.VISIBLE) {
                Video video = PlaybackPresenter.instance(getContext()).getVideo();
                mPipTitle.setText(video != null ? String.format("%s - %s", video.getTitle(), video.getAuthor()) : "");
            }
        }
    }

    private void updateAccountIcon() {
        // v32: delayed account refreshes may fire while the title view is being detached/rebuilt.
        // Never touch the orb in that transient state.
        if (!mIsAccountViewEnabled || mAccountView == null || getWindowToken() == null) {
            return;
        }

        Account current = MediaServiceManager.instance().getSelectedAccount();

        if (current != null) {
            if (current.getAvatarImageUrl() != null) {
                loadIcon(mAccountView, current.getAvatarImageUrl(), false);
            } else {
                mAccountView.setOrbColors(new Colors(
                    Color.rgb(36, 36, 36), Color.rgb(48, 48, 48), Color.WHITE));
            mAccountView.setOrbIcon(ContextCompat.getDrawable(getContext(), R.drawable.browse_title_account));
            }

            String accountName = current.getName() != null ? current.getName() : current.getEmail();
            if (mAccountLabel != null) {
                mAccountLabel.setText(accountName != null ? accountName : "");
            }
        } else {
            // v25 anonymous state: keep the account button in the exact same slot so the
            // title bar geometry does not jump, but do not render a fake channel named "None".
            // v32: signed-out state must keep the exact same circular account slot as a real
            // avatar. A transparent stock orb made the person glyph float by itself and changed
            // the perceived top-bar layout.
            mAccountView.setOrbColors(new Colors(0xFF272727, 0xFF333333,
                    ContextCompat.getColor(getContext(), R.color.orb_icon_color)));
            mAccountView.setOrbIcon(ContextCompat.getDrawable(getContext(), R.drawable.browse_title_account));
            if (mAccountLabel != null) {
                mAccountLabel.animate().cancel();
                mAccountLabel.setText("");
                mAccountLabel.setAlpha(0f);
                mAccountLabel.setVisibility(View.GONE);
                android.view.ViewGroup.LayoutParams params = mAccountLabel.getLayoutParams();
                if (params != null && params.width != 0) {
                    params.width = 0;
                    mAccountLabel.setLayoutParams(params);
                }
            }
        }
    }

    /**
     * SearchOrbView zooms its internal circle on focus. The account orb sits against the left
     * edge of the 64dp rail, so stock clipping can shave off that focus circle. Disable clipping
     * through the nearby title hierarchy and lift the orb above its siblings.
     */
    private void makeAccountOrbOverflowSafe() {
        if (mAccountView == null) {
            return;
        }

        mAccountView.setClipChildren(false);
        mAccountView.setClipToPadding(false);
        mAccountView.setClipToOutline(false);
        mAccountView.setTranslationZ(8f);

        android.view.ViewParent parent = mAccountView.getParent();
        int levels = 0;
        while (parent instanceof android.view.ViewGroup && levels < 5) {
            android.view.ViewGroup group = (android.view.ViewGroup) parent;
            group.setClipChildren(false);
            group.setClipToPadding(false);
            parent = group.getParent();
            levels++;
        }
    }

    private void updateLanguageIcon() {
        if (!mIsLanguageViewEnabled) {
            return;
        }

        // Use delay to fix icon initialization on app boot
        Utils.postDelayed(() -> {
            Locale locale = LocaleUtility.getCurrentLocale(getContext());
            loadIcon(mLanguageView, Utils.getCountryFlagUrl(locale.getCountry()), true); // flag server could be down
            TooltipCompatHandler.setTooltipText(mLanguageView, String.format("%s (%s)", locale.getDisplayCountry(), locale.getDisplayLanguage()));
        }, 100);
    }

    private void loadIcon(SearchOrbView view, String url, boolean useCache) {
        if (view == null || view.getWindowToken() == null) {
            return;
        }

        // The view with GONE visibility has zero width and height. Retry only while attached;
        // otherwise a stale title view could keep rescheduling itself after account/layout swaps.
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            Utils.postDelayed(() -> {
                if (view.getWindowToken() != null) {
                    loadIcon(view, url, useCache);
                }
            }, 500);
            return;
        }

        Context context = view.getContext();

        if (context instanceof Activity && !Utils.checkActivity((Activity) context)) {
            return;
        }

        // Size of the view might increase after icon change (bug on some firmwares). So, it's better to cache initial values.
        if (mIconWidth == 0 || mIconHeight == 0) {
            mIconWidth = view.getWidth();
            mIconHeight = view.getHeight();
        }

        try {
            loadIcon(context, view, url, mIconWidth, mIconHeight, useCache);
        } catch (ExceptionInInitializerError e) {
            // Glide Kivi error
            e.printStackTrace();
        }
    }

    private static void loadIcon(Context context, SearchOrbView view, String url, int iconWidth, int iconHeight, boolean useCache) {
        Glide.with(context)
                .load(url)
                .apply(ViewUtil.glideOptions())
                .diskCacheStrategy(useCache ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE)
                .circleCrop() // resize image
                .into(new SimpleTarget<Drawable>(iconWidth, iconHeight) {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        Colors orbColors = view.getOrbColors();
                        view.setOrbColors(new Colors(orbColors.color, orbColors.brightColor, Color.TRANSPARENT));
                        view.setOrbIcon(resource);
                    }
                });
    }

    @Override
    public void onDataChange() {
        try {
            updateButtonsVisibility();
            updateComponentsVisibility(mFlags);
        } catch (IllegalStateException e) {
            // Fragment BrowseFragment has not been attached yet.
            e.printStackTrace();
        }
    }
}
