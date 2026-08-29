package com.liskovsoft.smartyoutubetv2.tv.ui.browse;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.app.HeadersSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.BrowseFrameLayout;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.PageRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;
import androidx.leanback.widget.TitleHelper;
import androidx.leanback.widget.VerticalGridView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.misc.CrashRestorer;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.IconHeaderItemPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.dialog.ErrorDialogFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.headers.ExtendedHeadersSupportFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.misc.ProgressBarManager;

import java.util.HashMap;
import java.util.Map;

/*
 * Main class to show BrowseFragment with header and rows of videos
 */
public class BrowseFragment extends BrowseSupportFragment implements BrowseView {
    private static final String TAG = BrowseFragment.class.getSimpleName();
    private ArrayObjectAdapter mSectionRowAdapter;
    private BrowsePresenter mBrowsePresenter;
    private Map<Integer, BrowseSection> mSections;
    private BrowseSectionFragmentFactory mSectionFragmentFactory;
    private Handler mHandler;
    private ProgressBarManager mProgressBarManager;
    private boolean mIsFragmentCreated;
    private boolean mFocusOnContent;
    private boolean mSyncingRailSelection;
    private boolean mRailExpanded;
    // v26: visual activation is committed by click; DPAD hover stays separate.
    private int mCommittedHeaderPosition = 0;
    private ValueAnimator mRailWidthAnimator;
    private ValueAnimator mRailColorAnimator;
    private ValueAnimator mAccountLabelWidthAnimator;
    private Integer mContentAnchorInsetPx;
    private View mFocusObserverRoot;
    private ViewTreeObserver.OnGlobalFocusChangeListener mGlobalFocusChangeListener;
    private CrashRestorer mCrashRestorer;

    // Collapsed navigation is exactly the 64dp icon rail: one surface, no translucent extension.
    // Keeping it opaque also prevents the content/background underneath from creating the visual
    // impression of two different sidebar layers.
    private static final int RAIL_COLLAPSED_FALLBACK_DP = 64;
    private static final int RAIL_EXPANDED_DP = 220;
    private static final int RAIL_ANIMATION_MS = 165;
    private static final int RAIL_COLLAPSED_COLOR = 0xDC0F0F0F;
    private static final int RAIL_EXPANDED_COLOR = 0xFF0F0F0F;
    // Pull the feed left by the same 32dp removed from the old 96dp rail. This keeps the selected
    // card close to the rail and moves the outgoing card completely behind the single 64dp panel.
    private static final int CONTENT_COLLAPSED_OFFSET_DP = -48;
    // Preserve the already-correct expanded content position from v18.
    private static final int CONTENT_EXPANDED_OFFSET_DP = 124;
    private static final int TOPBAR_AFTER_RAIL_GAP_DP = 12;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);

        if (getContext() == null) {
            return;
        }
        
        mCrashRestorer = new CrashRestorer(getContext(), savedInstanceState);
        mIsFragmentCreated = true;

        mSections = new HashMap<>();
        mHandler = new Handler();
        mBrowsePresenter = BrowsePresenter.instance(getContext());
        mBrowsePresenter.setView(this);
        mProgressBarManager = new ProgressBarManager();

        setupAdapter();
        setupFragmentFactory();
        setupUi();

        enableMainFragmentScaling(false);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // Called when the activity is paused
        mCrashRestorer.persistHeaderIndex(outState, getSelectedPosition());
        mCrashRestorer.persistVideo(outState, mBrowsePresenter.getCurrentVideo());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);

        mProgressBarManager.setRootView((ViewGroup) root);
        installPermanentRailFocusBehavior(root);
        installImmediateRailFocusObserver(root);

        // Let horizontally scrolled cards render underneath the *entire* translucent rail.
        // Without this, the content hierarchy clips at its own left edge, which looks like an
        // opaque 64dp slab plus a second translucent slab even though the rail has one background.
        root.post(this::allowContentToRenderBehindRail);

        return root;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setupEventListeners();
        keepHeaderRailVisible();
        setRailExpanded(false, false);
        mHandler.postDelayed(() -> {
            keepHeaderRailVisible();
            setRailExpanded(false, false);
        }, 120);
        mHandler.postDelayed(() -> {
            keepHeaderRailVisible();
            setRailExpanded(false, false);
        }, 500);

        prepareEntranceTransition();

        mBrowsePresenter.onViewInitialized();
        mCommittedHeaderPosition = Math.max(0, getSelectedPosition());

        // Restore state after crash
        mCrashRestorer.restoreHeader((idx, video) -> {
            selectSection(idx, true);
            selectSectionItem(video);
        });
        mCrashRestorer.restorePlayback();
    }

    @Override
    public HeadersSupportFragment onCreateHeadersSupportFragment() {
        return new ExtendedHeadersSupportFragment();
    }

    private void setupEventListeners() {
        getHeadersSupportFragment().setOnHeaderViewSelectedListener(
                (viewHolder, row) -> {
                    if (row == null || mSyncingRailSelection) {
                        return;
                    }

                    int newPosition = getHeadersSupportFragment().getSelectedPosition();
                    if (newPosition < 0 || newPosition >= mSectionRowAdapter.size()) {
                        return;
                    }

                    // Header focus is SmartTube's actual section switch. Persist it separately from
                    // focus so the collapsed rail can keep the current destination filled.
                    mCommittedHeaderPosition = newPosition;

                    if (getSelectedPosition() != newPosition) {
                        mSyncingRailSelection = true;
                        try {
                            setSelectedPosition(newPosition, false);
                        } finally {
                            mSyncingRailSelection = false;
                        }
                    }

                    HeaderItem headerItem = row.getHeaderItem();
                    if (headerItem != null) {
                        mBrowsePresenter.onSectionFocused((int) headerItem.getId());
                    }

                    keepHeaderRailVisible();
                    applyRailItemVisuals(mRailExpanded, false);
                    // v24: account label follows the CURRENT focused sidebar item immediately.
                    syncTopBarWithRail(mRailExpanded, false);
                }
        );

        getHeadersSupportFragment().setOnHeaderClickedListener(
                (viewHolder, row) -> {
                    long headerId = row.getHeaderItem().getId();
                    int newPosition = indexOf(headerId);
                    // v26: ENTER/OK commits the focused sidebar item.
                    mCommittedHeaderPosition = Math.max(0, newPosition);
                    applyRailItemVisuals(mRailExpanded, false);

                    if (getHeadersSupportFragment().getSelectedPosition() != newPosition) {
                        getHeadersSupportFragment().setSelectedPosition(newPosition);
                    } else {
                        mBrowsePresenter.onSectionFocused((int) headerId);
                        focusOnContent();
                    }
                }
        );

        ((ExtendedHeadersSupportFragment) getHeadersSupportFragment()).setOnHeaderLongPressedListener(
                (viewHolder, row) -> {
                    long headerId = row.getHeaderItem().getId();

                    mBrowsePresenter.onSectionLongPressed((int) headerId);
                }
        );

        setOnSearchClickedListener(view -> SearchPresenter.instance(getContext()).startSearch(null));
    }

    private void setupFragmentFactory() {
        mSectionFragmentFactory = new BrowseSectionFragmentFactory(
                (row) -> {
                    focusOnContentIfNeeded();
                    mBrowsePresenter.onSectionFocused(getSelectedHeaderId());
                }
        );

        getMainFragmentRegistry().registerFragment(PageRow.class, mSectionFragmentFactory);
    }

    private int indexOf(long headerId) {
        for (int i = 0; i < mSectionRowAdapter.size(); i++) {
            PageRow row = (PageRow) mSectionRowAdapter.get(i);
            HeaderItem header = row.getHeaderItem();
            if (header.getId() == headerId) {
                return i;
            }
        }

        return 0;
    }

    private void setupAdapter() {
        // Map category results from the database to ListRow objects.
        // This Adapter is used to render the MainFragment sidebar labels.
        mSectionRowAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        setAdapter(mSectionRowAdapter);
    }

    private void setupUi() {
        if (getContext() == null) {
            return;
        }

        setHeadersState(HEADERS_ENABLED);
        setHeadersTransitionOnBackEnabled(false);

        int brandColorRes = Helpers.getThemeAttr(getContext(), R.attr.brandColor);

        updateBadge();

        // This title replaces badge in case one is null
        //setTitle(getString(R.string.browse_title));

        // Set fastLane (or headers) background color
        // The rail draws its own surface. Keeping Leanback's brand layer transparent prevents
        // the "double sidebar" effect (a second dark strip behind the rail).
        setBrandColor(Color.TRANSPARENT);

        // Set search icon color.
        setSearchAffordanceColor(ContextCompat.getColor(getContext(), R.color.yt_top_control_bg));

        setHeaderPresenterSelector(new PresenterSelector() {
            private final Map<Integer, Presenter> mPresenterMap = new HashMap<>();

            @Override
            public Presenter getPresenter(Object o) {
                Presenter presenter = mPresenterMap.get(o.hashCode());

                if (presenter == null) {
                    presenter = new IconHeaderItemPresenter(getHeaderResId(o), getIconUrl(o));
                    mPresenterMap.put(o.hashCode(), presenter);
                }

                return presenter;
            }

            private int getHeaderResId(Object o) {
                if (o instanceof PageRow) {
                    return ((SectionHeaderItem) ((PageRow) o).getHeaderItem()).getResId();
                }

                return -1;
            }

            private String getIconUrl(Object o) {
                if (o instanceof PageRow) {
                    return ((SectionHeaderItem) ((PageRow) o).getHeaderItem()).getIconUrl();
                }

                return null;
            }
        });
    }

    private int getSelectedHeaderId() {
        if (getSelectedPosition() >= mSectionRowAdapter.size()) {
            return -1;
        }

        return (int) ((PageRow) mSectionRowAdapter.get(getSelectedPosition())).getHeaderItem().getId();
    }
    
    public void updateErrorIfEmpty(ErrorFragmentData data) {
        mHandler.postDelayed(() -> showErrorIfEmpty(data), 500); // need delay because header may be not updated
    }

    @Override
    public void showError(ErrorFragmentData data) {
        replaceMainFragment(new ErrorDialogFragment(data));
    }

    private void showErrorIfEmpty(ErrorFragmentData data) {
        if (isEmpty()) {
            replaceMainFragment(new ErrorDialogFragment(data));
        }
    }

    private void replaceMainFragment(Fragment fragment) {
        //Object mainFragment = Helpers.getField(this,"mMainFragment");
        Fragment mainFragment = getMainFragment();

        if (mainFragment != null && fragment != null && mainFragment != fragment) {
            Helpers.setField(this, "mMainFragment", fragment);

            FragmentTransaction ft = getChildFragmentManager().beginTransaction();
            ft.replace(R.id.scale_frame, fragment);
            //mFocusOnContent = !isShowingHeaders(); // Fix focus lost when error fragment shown and sidebar is hidden
            mFocusOnContent = hasFocus(); // Maintain focus
            ft.runOnCommit(this::focusOnContentIfNeeded);
            ft.commitAllowingStateLoss(); // FIX: "Can not perform this action after onSaveInstanceState"
        }
    }

    @Override
    public void addSection(int index, BrowseSection section) {
        if (section == null) {
            return;
        }

        if (mSections.get(section.getId()) != null && (index == -1 || indexOf(section.getId()) == index)) {
            return;
        }

        removeSection(section);

        mSections.put(section.getId(), section);
        createHeader(index, section);
    }

    @Override
    public void removeSection(BrowseSection section) {
        if (section == null) {
            return;
        }

        mSections.remove(section.getId());
        removeHeader(section);
    }

    @Override
    public void removeAllSections() {
        mSections.clear();
        mSectionRowAdapter.clear();
    }

    @Override
    public void updateSection(VideoGroup group) {
        restoreMainFragment();

        mSectionFragmentFactory.updateCurrentFragment(group);

        fixInvisibleSearchOrb();
    }

    @Override
    public void updateSection(SettingsGroup group) {
        restoreMainFragment();

        mSectionFragmentFactory.updateCurrentFragment(group);
    }

    @Override
    public void selectSection(int index, boolean focusOnContent) {
        if (index >= 0 && mSectionRowAdapter.size() > 0) {
            mCommittedHeaderPosition = Math.min(index, mSectionRowAdapter.size() - 1);
            mFocusOnContent = focusOnContent; // focus after header transition

            // Fix refresh current section
            if (getSelectedPosition() == index) {
                // update section manually
                // headers transition event not fired on the same index
                focusOnContentIfNeeded();
                mBrowsePresenter.onSectionFocused(getSelectedHeaderId());
            }

            // Need select again if current header is removed previously (can't check for it right now)
            // Fallback to the last section if index above size
            setSelectedPosition(index < mSectionRowAdapter.size() ? index : mSectionRowAdapter.size() - 1, false);
        }
    }

    @Override
    public void focusOnContent() {
        if (getMainFragment() != null && getMainFragment().getView() != null) {
            getMainFragment().getView().requestFocus();
        }
        keepHeaderRailVisible();
        setRailExpanded(false, true);
    }

    /**
     * The stock Leanback layout wraps HeadersSupportFragment in browse_headers_dock and gives that
     * dock 50dp end-padding "for shadow". Earlier sidebar patches resized/painted only the child
     * HeadersSupportFragment. That left the 50dp dock as a second visual/geometry layer, which is
     * exactly the strip visible over/next to the icons in the screenshots.
     *
     * The DOCK is the single owner of sidebar width/background. Its stock shadow padding is removed.
     * The child header fragment always fills the dock and stays transparent. Collapsed and expanded
     * therefore both render as one continuous dark surface, never as two overlapping slabs.
     */
    private void keepHeaderRailVisible() {
        HeadersSupportFragment headersFragment = getHeadersSupportFragment();
        View headersView = headersFragment != null ? headersFragment.getView() : null;
        View headersDock = getHeadersDock();
        if (headersView == null || headersDock == null) {
            return;
        }

        headersView.animate().cancel();
        headersView.setVisibility(View.VISIBLE);
        headersView.setAlpha(1f);
        headersView.setTranslationX(0f);
        headersView.setBackgroundColor(Color.TRANSPARENT);

        // Remove Leanback's built-in 50dp end padding from browse_headers_dock. That padding is for
        // stock header shadows and must not exist in an overlay navigation rail.
        headersDock.setPadding(0, headersDock.getPaddingTop(), 0, headersDock.getPaddingBottom());
        headersDock.setVisibility(View.VISIBLE);
        headersDock.setAlpha(1f);
        headersDock.setTranslationX(0f);
        headersDock.setBackgroundColor(mRailExpanded ? RAIL_EXPANDED_COLOR : RAIL_COLLAPSED_COLOR);

        ViewGroup.LayoutParams dockRaw = headersDock.getLayoutParams();
        if (dockRaw instanceof MarginLayoutParams) {
            MarginLayoutParams dockParams = (MarginLayoutParams) dockRaw;
            dockParams.setMarginStart(0);
            dockParams.setMarginEnd(0);
            dockParams.width = mRailExpanded ? dp(RAIL_EXPANDED_DP) : getCollapsedRailWidthPx();
            headersDock.setLayoutParams(dockParams);
        } else if (dockRaw != null) {
            dockRaw.width = mRailExpanded ? dp(RAIL_EXPANDED_DP) : getCollapsedRailWidthPx();
            headersDock.setLayoutParams(dockRaw);
        }

        if (headersDock instanceof ViewGroup) {
            ViewGroup dockGroup = (ViewGroup) headersDock;
            dockGroup.setClipChildren(false);
            dockGroup.setClipToPadding(false);
        }

        // The fragment view never paints a second sidebar surface. It just fills the dock.
        ViewGroup.LayoutParams headerRaw = headersView.getLayoutParams();
        if (headerRaw != null) {
            headerRaw.width = ViewGroup.LayoutParams.MATCH_PARENT;
            headersView.setLayoutParams(headerRaw);
        }
        headersView.setPadding(0, headersView.getPaddingTop(), 0, headersView.getPaddingBottom());
        if (headersView instanceof ViewGroup) {
            ((ViewGroup) headersView).setClipChildren(false);
        }

        VerticalGridView rail = headersFragment.getVerticalGridView();
        if (rail != null) {
            ViewGroup.LayoutParams railParams = rail.getLayoutParams();
            if (railParams != null) {
                railParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                rail.setLayoutParams(railParams);
            }

            rail.setChildrenVisibility(View.VISIBLE);
            rail.setFocusSearchDisabled(false);
            rail.setClipChildren(false);
            rail.setClipToPadding(false);
            rail.setPadding(0, rail.getPaddingTop(), 0, rail.getPaddingBottom());
            rail.setVerticalSpacing(dp(2));
            rail.setBackgroundColor(Color.TRANSPARENT);
            rail.setAlpha(1f);
        }

        allowContentToRenderBehindRail();
        applyRailItemVisuals(mRailExpanded, false);

        // Title bar lives in a separate Leanback container. Re-align horizontally after every rail
        // layout pass. Do NOT pin it vertically: the avatar and account label must scroll away
        // together when Leanback hides the title bar.
        headersDock.post(() -> {
            alignAccountOrbToRail();
            alignSearchControlsToContent(mRailExpanded, false);
        });
    }

    private View getHeadersDock() {
        View root = getView();
        return root != null ? root.findViewById(R.id.browse_headers_dock) : null;
    }

    private View getContentDock() {
        View root = getView();
        return root != null ? root.findViewById(R.id.browse_container_dock) : null;
    }

    /**
     * Navigation/content behavior:
     * - collapsed icon rail is one lightly translucent 64dp panel; content is pulled left enough that the
     *   outgoing card disappears fully behind that panel rather than peeking out beside it;
     * - expanded navigation is a real opaque panel: content slides right to clear the full menu,
     *   so cards/row titles never remain underneath the expanded navigation.
     */
    private void setMainContentOffset(boolean expanded, boolean animate) {
        View contentDock = getContentDock();
        if (contentDock == null) {
            return;
        }

        float targetTranslationX = expanded ? dp(CONTENT_EXPANDED_OFFSET_DP) : dp(CONTENT_COLLAPSED_OFFSET_DP);
        contentDock.animate().cancel();

        if (animate) {
            contentDock.animate()
                    .translationX(targetTranslationX)
                    .setDuration(RAIL_ANIMATION_MS)
                    .start();
        } else {
            contentDock.setTranslationX(targetTranslationX);
        }
    }

    /**
     * Account/avatar group follows the navigation icon axis. Search controls are deliberately a
     * separate group and follow the content/card axis instead.
     */
    private void alignAccountOrbToRail() {
        View root = getView();
        HeadersSupportFragment headersFragment = getHeadersSupportFragment();
        if (root == null || headersFragment == null) {
            return;
        }

        View accountControls = root.findViewById(R.id.yt_account_controls);
        View accountOrb = root.findViewById(R.id.account_orb);
        VerticalGridView rail = headersFragment.getVerticalGridView();
        if (accountControls == null || accountOrb == null || rail == null || rail.getChildCount() == 0 ||
                accountOrb.getWidth() == 0) {
            return;
        }

        View firstHeader = rail.getChildAt(0);
        View headerIcon = firstHeader != null ? firstHeader.findViewById(R.id.header_icon) : null;
        if (headerIcon == null || headerIcon.getWidth() == 0) {
            return;
        }

        int[] accountLocation = new int[2];
        int[] iconLocation = new int[2];
        accountOrb.getLocationOnScreen(accountLocation);
        headerIcon.getLocationOnScreen(iconLocation);

        float accountCenterX = accountLocation[0] + accountOrb.getWidth() / 2f;
        float iconCenterX = iconLocation[0] + headerIcon.getWidth() / 2f;
        float deltaX = iconCenterX - accountCenterX;

        if (Math.abs(deltaX) >= 0.5f) {
            accountControls.setTranslationX(accountControls.getTranslationX() + deltaX);
        }
    }

    /**
     * Expanded rail: show the selected YouTube account/channel name beside the avatar. The mic/search
     * group is positioned independently on the exact content/card start. Collapsed rail: hide the
     * account name again while keeping mic/search aligned with the normal content start.
     */
    private void syncTopBarWithRail(boolean expanded, boolean animate) {
        View root = getView();
        View headersDock = getHeadersDock();
        if (root == null || headersDock == null) {
            return;
        }

        View accountLabel = root.findViewById(R.id.yt_account_label);
        View accountOrb = root.findViewById(R.id.account_orb);
        if (accountLabel == null || accountOrb == null) {
            return;
        }

        // v24: never capture the old section/expanded state before posting this runnable.
        // Rapid DPAD moves can queue multiple callbacks; every queued callback must resolve
        // against the CURRENT rail state when it actually runs. The account name is therefore
        // binary: visible only when Home (header position 0) is focused AND the rail is expanded.
        headersDock.post(() -> {
            alignAccountOrbToRail();

            HeadersSupportFragment headersFragment = getHeadersSupportFragment();
            int focusedHeaderPosition = headersFragment != null
                    ? headersFragment.getSelectedPosition() : -1;
            boolean railExpandedNow = mRailExpanded;
            // v25: only a real signed-in account has a non-empty channel label. Anonymous
            // sessions keep the same account-button slot, but never show "None" or reserve width.
            boolean hasAccountName = accountLabel instanceof android.widget.TextView &&
                    ((android.widget.TextView) accountLabel).length() > 0;
            boolean showAccountLabel = railExpandedNow && focusedHeaderPosition == 0 && hasAccountName;

            if (mAccountLabelWidthAnimator != null) {
                mAccountLabelWidthAnimator.cancel();
                mAccountLabelWidthAnimator = null;
            }
            accountLabel.animate().cancel();

            int targetLabelWidth = 0;
            if (showAccountLabel) {
                int[] dockLocation = new int[2];
                int[] labelLocation = new int[2];
                headersDock.getLocationOnScreen(dockLocation);
                accountLabel.getLocationOnScreen(labelLocation);
                int labelStart = labelLocation[0];
                int labelEnd = dockLocation[0] + dp(RAIL_EXPANDED_DP) - dp(12);
                targetLabelWidth = Math.max(0, labelEnd - labelStart);
            }

            ViewGroup.LayoutParams params = accountLabel.getLayoutParams();
            if (params != null) {
                params.width = targetLabelWidth;
                accountLabel.setLayoutParams(params);
            }

            // No per-tab fading or width animation. This state changes in one frame.
            accountLabel.setAlpha(showAccountLabel ? 1f : 0f);
            accountLabel.setVisibility(showAccountLabel ? View.VISIBLE : View.GONE);

            // Mic + Search keep following the current rail/content geometry independently.
            alignSearchControlsToContent(railExpandedNow, animate);
        });
    }

    private void alignSearchControlsToContent(boolean expanded, boolean animate) {
        View root = getView();
        View contentDock = getContentDock();
        if (root == null || contentDock == null) {
            return;
        }

        View searchControls = root.findViewById(R.id.yt_search_controls);
        if (searchControls == null || searchControls.getWidth() == 0) {
            return;
        }

        if (mContentAnchorInsetPx == null) {
            View mainFragmentView = getMainFragment() != null ? getMainFragment().getView() : null;
            View anchor = mainFragmentView != null ? findFirstContentAnchor(mainFragmentView) : null;
            if (anchor != null && anchor.getWidth() > 0) {
                int[] anchorLocation = new int[2];
                int[] dockLocation = new int[2];
                anchor.getLocationOnScreen(anchorLocation);
                contentDock.getLocationOnScreen(dockLocation);
                mContentAnchorInsetPx = Math.max(0, anchorLocation[0] - dockLocation[0]);
            }
        }

        int inset = mContentAnchorInsetPx != null ? mContentAnchorInsetPx : dp(72);
        int[] dockLocation = new int[2];
        int[] searchLocation = new int[2];
        contentDock.getLocationOnScreen(dockLocation);
        searchControls.getLocationOnScreen(searchLocation);

        float baseDockX = dockLocation[0] - contentDock.getTranslationX();
        float targetContentTranslation = expanded ? dp(CONTENT_EXPANDED_OFFSET_DP) : dp(CONTENT_COLLAPSED_OFFSET_DP);
        float targetSearchX = baseDockX + targetContentTranslation + inset;
        float delta = targetSearchX - searchLocation[0];
        float targetTranslation = searchControls.getTranslationX() + delta;

        searchControls.animate().cancel();
        if (animate) {
            searchControls.animate()
                    .translationX(targetTranslation)
                    .setDuration(RAIL_ANIMATION_MS)
                    .start();
        } else {
            searchControls.setTranslationX(targetTranslation);
        }
    }

    private View findFirstContentAnchor(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (root.getId() == R.id.yt_thumbnail_shell && root.getWidth() > 0) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            View best = null;
            int bestX = Integer.MAX_VALUE;
            for (int i = 0; i < group.getChildCount(); i++) {
                View candidate = findFirstContentAnchor(group.getChildAt(i));
                if (candidate != null) {
                    int[] location = new int[2];
                    candidate.getLocationOnScreen(location);
                    // Ignore cards that are already mostly scrolled off screen to the left.
                    if (location[0] >= -dp(24) && location[0] < bestX) {
                        best = candidate;
                        bestX = location[0];
                    }
                }
            }
            return best;
        }
        return null;
    }

    /**
     * Collapsed surface is deliberately wider than the 64dp icon slot. This matches YouTube TV: the
     * translucent rail ends just before the card keyline, so the previous card disappears *under*
     * the rail rather than leaving an exposed strip beside it. The content itself still only moves
     * by the tiny 8dp underlap configured above.
     */
    private int getCollapsedRailWidthPx() {
        return dp(RAIL_COLLAPSED_FALLBACK_DP);
    }

    private void setRailExpanded(boolean expanded, boolean animate) {
        View headersDock = getHeadersDock();
        if (headersDock == null) {
            mRailExpanded = expanded;
            setMainContentOffset(expanded, animate);
            syncTopBarWithRail(expanded, animate);
            return;
        }

        int targetWidth = expanded ? dp(RAIL_EXPANDED_DP) : getCollapsedRailWidthPx();
        int targetColor = expanded ? RAIL_EXPANDED_COLOR : RAIL_COLLAPSED_COLOR;

        if (mRailWidthAnimator != null) {
            mRailWidthAnimator.cancel();
        }
        if (mRailColorAnimator != null) {
            mRailColorAnimator.cancel();
        }
        if (mAccountLabelWidthAnimator != null) {
            mAccountLabelWidthAnimator.cancel();
        }

        mRailExpanded = expanded;
        setMainContentOffset(expanded, animate);
        syncTopBarWithRail(expanded, animate);

        // Always neutralize the stock dock padding before measuring/animating its width.
        headersDock.setPadding(0, headersDock.getPaddingTop(), 0, headersDock.getPaddingBottom());

        ViewGroup.LayoutParams raw = headersDock.getLayoutParams();
        int currentWidth = raw != null && raw.width > 0 ? raw.width : targetWidth;

        if (!animate || currentWidth == targetWidth) {
            if (raw != null) {
                raw.width = targetWidth;
                headersDock.setLayoutParams(raw);
            }
            headersDock.setBackgroundColor(targetColor);
            applyRailItemVisuals(expanded, false);
            keepHeaderChildTransparentAndFilled();
            return;
        }

        mRailWidthAnimator = ValueAnimator.ofInt(currentWidth, targetWidth);
        mRailWidthAnimator.setDuration(RAIL_ANIMATION_MS);
        mRailWidthAnimator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams params = headersDock.getLayoutParams();
            if (params != null) {
                params.width = (int) animation.getAnimatedValue();
                headersDock.setLayoutParams(params);
            }
        });
        mRailWidthAnimator.start();

        int currentColor = expanded ? RAIL_COLLAPSED_COLOR : RAIL_EXPANDED_COLOR;
        mRailColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), currentColor, targetColor);
        mRailColorAnimator.setDuration(RAIL_ANIMATION_MS);
        mRailColorAnimator.addUpdateListener(animation ->
                headersDock.setBackgroundColor((int) animation.getAnimatedValue()));
        mRailColorAnimator.start();

        applyRailItemVisuals(expanded, true);
        keepHeaderChildTransparentAndFilled();
    }

    private void keepHeaderChildTransparentAndFilled() {
        HeadersSupportFragment headersFragment = getHeadersSupportFragment();
        View headersView = headersFragment != null ? headersFragment.getView() : null;
        if (headersView == null) {
            return;
        }

        headersView.setBackgroundColor(Color.TRANSPARENT);
        ViewGroup.LayoutParams params = headersView.getLayoutParams();
        if (params != null && params.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            headersView.setLayoutParams(params);
        }
    }

    private void applyRailItemVisuals(boolean expanded, boolean animate) {
        HeadersSupportFragment headersFragment = getHeadersSupportFragment();
        if (headersFragment == null) {
            return;
        }

        VerticalGridView rail = headersFragment.getVerticalGridView();
        if (rail == null) {
            return;
        }

        float targetAlpha = expanded ? 1f : 0f;
        float targetTranslation = expanded ? 0f : -dp(8);
        int focusedPosition = rail.getSelectedPosition();
        // Keep one persistent "current section" independent from rail focus. mCommittedHeaderPosition
        // is updated whenever SmartTube switches section, and does not get cleared when focus moves
        // into the feed/search controls.
        int activeSectionPosition = Math.max(0,
                Math.min(mCommittedHeaderPosition, Math.max(0, mSectionRowAdapter.size() - 1)));
        boolean railHasFocus = rail.hasFocus();

        for (int i = 0; i < rail.getChildCount(); i++) {
            View child = rail.getChildAt(i);
            int adapterPosition = rail.getChildAdapterPosition(child);
            boolean isFocusedItem = expanded && railHasFocus && adapterPosition == focusedPosition;
            // v27: Leanback changes the active section as soon as a header receives DPAD focus.
            // There is no separate reliable "committed" click state for normal sidebar navigation.
            // Therefore the white pill + black icon/text must follow the currently focused header
            // while the rail owns focus. As soon as focus leaves the rail (Search/content/account),
            // the pill disappears and every header returns to white.
            boolean isActivatedItem = isFocusedItem;

            // Visual states:
            // inactive -> white icon/text without a pill
            // focused/current header -> white pill with black icon/text
            // focus leaves navbar -> pill removed immediately, text/icon back to white
            child.setSelected(isFocusedItem);
            // v28: header_surface has duplicateParentState=true. The activated state therefore
            // has to be set on the ROW itself; setting it only on header_surface is ignored by
            // the drawable-state duplication and caused black text/icon with no white pill.
            child.setActivated(isActivatedItem);
            child.refreshDrawableState();

            View surface = child.findViewById(R.id.header_surface);
            if (surface != null) {
                surface.setActivated(isActivatedItem);
                surface.refreshDrawableState();
            }

            android.widget.ImageView icon = child.findViewById(R.id.header_icon);
            if (icon != null) {
                // YouTube TV-style icon weight:
                // - the section currently being viewed stays FILLED while focus is in the feed;
                // - every destination you can move to uses the thin OUTLINE version;
                // - moving onto a header fills that icon; selecting/focusing it in the expanded
                //   navbar still turns the filled icon black on the white selection pill.
                boolean shouldUseFilledIcon = adapterPosition == activeSectionPosition || isFocusedItem;
                // Activated survives Leanback focus/select bookkeeping; selected remains useful for
                // the temporary hover state while the expanded rail owns focus.
                icon.setActivated(shouldUseFilledIcon);
                icon.setSelected(shouldUseFilledIcon);
                icon.refreshDrawableState();
                icon.setAlpha(1f);
                icon.setColorFilter(isActivatedItem ? Color.BLACK : Color.WHITE, PorterDuff.Mode.SRC_IN);
            }

            android.widget.TextView label = child.findViewById(R.id.header_label);
            if (label != null) {
                label.setSelected(isFocusedItem);
                label.setTextColor(isActivatedItem ? Color.BLACK : Color.WHITE);
                label.animate().cancel();
                if (animate) {
                    label.animate()
                            .alpha(targetAlpha)
                            .translationX(targetTranslation)
                            .setDuration(expanded ? 150 : 100)
                            .setStartDelay(expanded ? 35 : 0)
                            .start();
                } else {
                    label.setAlpha(targetAlpha);
                    label.setTranslationX(targetTranslation);
                }
            }

            View divider = child.findViewById(R.id.header_divider);
            if (divider != null && divider.getVisibility() == View.VISIBLE) {
                divider.animate().cancel();
                if (animate) {
                    divider.animate()
                            .alpha(targetAlpha)
                            .setDuration(expanded ? 150 : 90)
                            .setStartDelay(expanded ? 55 : 0)
                            .start();
                } else {
                    divider.setAlpha(targetAlpha);
                }
            }
        }
    }

    /**
     * BrowseSupportFragment normally moves the entire headers fragment on and off screen. We keep
     * the original focus routing, but replace only that visual transition with our overlay width
     * animation. This means DPAD-left from the first card still enters the navigation rail and
     * DPAD-right returns to the current video row.
     */
    private void installPermanentRailFocusBehavior(View root) {
        if (root == null) {
            return;
        }

        BrowseFrameLayout browseFrame = root.findViewById(R.id.browse_frame);
        if (browseFrame == null) {
            return;
        }

        final BrowseFrameLayout.OnChildFocusListener leanbackListener = browseFrame.getOnChildFocusListener();

        browseFrame.setOnChildFocusListener(new BrowseFrameLayout.OnChildFocusListener() {
            @Override
            public boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
                return leanbackListener != null &&
                        leanbackListener.onRequestFocusInDescendants(direction, previouslyFocusedRect);
            }

            @Override
            public void onRequestChildFocus(View child, View focused) {
                keepHeaderRailVisible();

                View headersView = getHeadersSupportFragment() != null
                        ? getHeadersSupportFragment().getView() : null;
                boolean headerHasFocus = headersView != null &&
                        (headersView == focused || headersView.hasFocus() || isDescendantOf(focused, headersView));

                setRailExpanded(headerHasFocus, true);
            }
        });
    }

    /**
     * Leanback's child-focus callback does not fire for every nested focus hop. In particular,
     * the first card can receive focus while the expanded rail is still visible, and only the
     * next DPAD_RIGHT causes the rail to collapse. Observe the real global focus instead: the
     * moment focus enters the main content, collapse; the moment it enters the headers, expand.
     */
    private void installImmediateRailFocusObserver(View root) {
        if (root == null) {
            return;
        }

        mFocusObserverRoot = root;
        mGlobalFocusChangeListener = (oldFocus, newFocus) -> {
            if (newFocus == null || getView() == null) {
                return;
            }

            View headersView = getHeadersSupportFragment() != null
                    ? getHeadersSupportFragment().getView() : null;
            Fragment mainFragment = getMainFragment();
            View mainView = mainFragment != null ? mainFragment.getView() : null;

            boolean inHeaders = headersView != null &&
                    (newFocus == headersView || isDescendantOf(newFocus, headersView));
            boolean inMainContent = mainView != null &&
                    (newFocus == mainView || isDescendantOf(newFocus, mainView));

            if (inMainContent && mRailExpanded) {
                keepHeaderRailVisible();
                setRailExpanded(false, true);
            } else if (inHeaders && !mRailExpanded) {
                keepHeaderRailVisible();
                setRailExpanded(true, true);
            }

            // v26: Search/account/title live outside both trees. Refresh after every focus hop
            // so a label can never stay black after the white navbar pill is gone.
            root.post(() -> {
                applyRailItemVisuals(mRailExpanded, false);
                syncTopBarWithRail(mRailExpanded, false);
            });

        };

        root.getViewTreeObserver().addOnGlobalFocusChangeListener(mGlobalFocusChangeListener);
    }

    /**
     * The collapsed rail has only ONE translucent surface. The apparent solid inner strip came
     * from clipping: the previous card was not allowed to draw left of the content/grid bounds.
     * Disable clipping through the relevant container chain so the outgoing card can continue
     * underneath the icon column, exactly like the YouTube TV overlay.
     */
    private void allowContentToRenderBehindRail() {
        View contentDock = getContentDock();
        if (contentDock == null) {
            return;
        }

        View current = contentDock;
        int levels = 0;
        while (current != null && levels < 3) {
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                group.setClipChildren(false);
                group.setClipToPadding(false);
            }

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
            levels++;
        }

        Fragment mainFragment = getMainFragment();
        View mainView = mainFragment != null ? mainFragment.getView() : null;
        if (mainView instanceof ViewGroup) {
            ((ViewGroup) mainView).setClipChildren(false);
            ((ViewGroup) mainView).setClipToPadding(false);
        }
    }

    /**
     * Enter the navigation rail directly from the left-most card in a row. Leanback normally
     * relies on its headers transition for this, but our always-visible overlay rail no longer
     * participates in that transition. This restores the expected TV behavior: LEFT through the
     * row, then one more LEFT opens/focuses the rail at the current section.
     */
    public boolean enterRailFromContentIfNeeded(View focusedView) {
        Fragment mainFragment = getMainFragment();
        View mainView = mainFragment != null ? mainFragment.getView() : null;

        if (focusedView == null || mainView == null || !isDescendantOf(focusedView, mainView)) {
            return false;
        }

        // If Android can still find another focusable view to the left inside the content, let
        // normal row navigation handle it. Only intercept at the actual left edge of the row.
        View nextLeft = focusedView.focusSearch(View.FOCUS_LEFT);
        if (nextLeft != null && nextLeft != focusedView && isDescendantOf(nextLeft, mainView)) {
            return false;
        }

        HeadersSupportFragment headersFragment = getHeadersSupportFragment();
        if (headersFragment == null) {
            return false;
        }

        VerticalGridView rail = headersFragment.getVerticalGridView();
        if (rail == null) {
            return false;
        }

        int targetPosition = Math.max(0, Math.min(getSelectedPosition(), mSectionRowAdapter.size() - 1));
        headersFragment.setSelectedPosition(targetPosition);
        rail.setSelectedPosition(targetPosition);

        keepHeaderRailVisible();
        setRailExpanded(true, true);

        // Requesting focus on the grid lets BaseGridView forward it to its selected child. Post a
        // second request because an item can still be laid out while the rail width animates.
        boolean focused = rail.requestFocus();
        rail.post(() -> {
            rail.setSelectedPosition(targetPosition);
            rail.requestFocus();
        });

        return focused || rail.isFocusable();
    }

    private boolean isDescendantOf(View view, View ancestor) {
        if (view == null || ancestor == null) {
            return false;
        }

        View current = view;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
        }

        return false;
    }

    private int dp(int value) {
        if (getContext() == null) {
            return value;
        }

        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    /**
     * Usually called after header transition or fragment transaction
     */
    private void focusOnContentIfNeeded() {
        if (mFocusOnContent) {
            focusOnContent();
            mFocusOnContent = false;
        }
    }

    private boolean hasFocus() {
        if (getMainFragment() == null || getMainFragment().getView() == null) {
            return false;
        }

        return getMainFragment().getView().hasFocus();
    }

    @Override
    public void selectSectionItem(int index) {
        if (index >= 0) {
            mSectionFragmentFactory.setCurrentFragmentItemIndex(index);
        }
    }

    @Override
    public void selectSectionItem(Video item) {
        if (item != null) {
            mSectionFragmentFactory.selectCurrentFragmentItem(item);
        }
    }

    /**
     * Fix: IllegalStateException: "Can not perform this action after onSaveInstanceState"
     */
    private void startHeadersTransitionSafe(boolean withHeaders) {
        // Fix: IllegalStateException: "Can not perform this action after onSaveInstanceState"
        if (!Utils.checkActivity(getActivity())) {
            return;
        }

        try {
            startHeadersTransition(withHeaders);
        } catch (IllegalStateException e) {
            // NOP
        }
    }

    /**
     * Restore after the error fragment
     */
    private void restoreMainFragment() {
        Fragment currentFragment = mSectionFragmentFactory.getCurrentFragment();

        if (currentFragment != null) {
            replaceMainFragment(currentFragment);
        }
    }

    private void createHeader(int index, BrowseSection header) {
        HeaderItem headerItem = new SectionHeaderItem(header);

        PageRow pageRow = new PageRow(headerItem);
        if (index == -1 || mSectionRowAdapter.size() < index) {
            mSectionRowAdapter.add(pageRow); // add to the end
        } else {
            mSectionRowAdapter.add(index, pageRow);
        }
    }

    private void removeHeader(BrowseSection header) {
        Object foundHeader = null;

        for (Object item : mSectionRowAdapter.unmodifiableList()) {
            if (((PageRow) item).getHeaderItem().getId() == header.getId()) {
                foundHeader = item;
                break;
            }
        }

        if (foundHeader != null) {
            mSectionRowAdapter.remove(foundHeader);
        }
    }

    @Override
    public void clearSection(BrowseSection section) {
        mSectionFragmentFactory.clearCurrentFragment();
    }

    @Override
    public void onDestroyView() {
        if (mFocusObserverRoot != null && mGlobalFocusChangeListener != null) {
            ViewTreeObserver observer = mFocusObserverRoot.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalFocusChangeListener(mGlobalFocusChangeListener);
            }
        }
        mFocusObserverRoot = null;
        mGlobalFocusChangeListener = null;

        mSectionFragmentFactory.cleanup();
        if (mRailWidthAnimator != null) {
            mRailWidthAnimator.cancel();
        }
        if (mRailColorAnimator != null) {
            mRailColorAnimator.cancel();
        }
        if (mAccountLabelWidthAnimator != null) {
            mAccountLabelWidthAnimator.cancel();
        }

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBrowsePresenter.onViewDestroyed();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (!mIsFragmentCreated) {
            mBrowsePresenter.onViewPaused();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!mIsFragmentCreated) {
            mBrowsePresenter.onViewResumed();
        }

        mIsFragmentCreated = false;
    }

    /**
     * Fix suddenly invisible search orb<br/>
     * Could happen on topmost category when the page partially scrolled<br/>
     * More info: {@link TitleHelper}
     */
    private void fixInvisibleSearchOrb() {
        if (isShowingTitle() && getTitleView() != null && getTitleView().getVisibility() != View.VISIBLE) {
            getTitleView().setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void showProgressBar(boolean show) {
        Runnable callback;

        if (show) {
            callback = mProgressBarManager::show;
        } else {
            callback = mProgressBarManager::hide;
        }

        // Essential. Need to run on the main thread.
        new Handler(Looper.getMainLooper()).post(callback);
    }

    @Override
    public boolean isProgressBarShowing() {
        return mProgressBarManager.isShowing();
    }

    @Override
    public boolean isEmpty() {
        return mSectionFragmentFactory == null || mSectionFragmentFactory.isEmpty();
    }

    @Override
    public void updateBadge() {
        if (getContext() == null) {
            return;
        }

        // The reference uses a full wordmark at the top-right. Keep SmartTube branding there
        // instead of a bridge application's single-letter icon.
        setBadgeDrawable(ContextCompat.getDrawable(getContext(), R.mipmap.app_logo_semi_red));
    }
}
