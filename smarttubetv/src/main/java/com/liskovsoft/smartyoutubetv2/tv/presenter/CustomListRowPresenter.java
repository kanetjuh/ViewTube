package com.liskovsoft.smartyoutubetv2.tv.presenter;

import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.RowPresenter;

/**
 * YouTube-style row presenter.
 *
 * Besides keeping SmartTube's custom cards at a fixed size (no Leanback zoom/shadow/dim),
 * each newly focused card is snapped to the exact same left keyline as the first card.
 * This makes a single DPAD_RIGHT move equal one complete card step: the previous card moves
 * fully behind the collapsed translucent navigation rail instead of remaining half visible.
 */
public class CustomListRowPresenter extends ListRowPresenter {
    public CustomListRowPresenter() {
        super(FocusHighlight.ZOOM_FACTOR_NONE, false);
        setSelectEffectEnabled(false);
        setShadowEnabled(false);
    }

    @Override
    protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
        super.initializeRowViewHolder(holder);

        ViewHolder rowHolder = (ViewHolder) holder;
        HorizontalGridView grid = rowHolder.getGridView();

        // Leanback defaults to a 50% window keyline. That is why the focused second card used to
        // remain around the middle of the screen and the previous card stayed half visible.
        // Keep focus scrolling aligned, but move the keyline to the row's real left content edge.
        // The outgoing card must be able to render past the grid's left bound and underneath
        // the translucent navigation overlay. Otherwise the first icon-column width looks opaque.
        grid.setClipChildren(false);
        grid.setClipToPadding(false);

        grid.setFocusScrollStrategy(BaseGridView.FOCUS_SCROLL_ALIGNED);
        grid.setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE);
        grid.setWindowAlignmentOffset(grid.getPaddingLeft());
        grid.setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);

        // Align the item's own left edge to that keyline. Do not use the item's center.
        grid.setItemAlignmentOffset(0);
        grid.setItemAlignmentOffsetPercent(BaseGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
        grid.setItemAlignmentOffsetWithPadding(false);
    }
}
