package com.airmusic.player.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Locks its child content to the reference TV-box aspect ratio (16:9),
 * centered in whatever screen the app runs on. On the box itself the sizes
 * match exactly, so nothing changes; on phones / tablets the content keeps
 * the box's proportions with the blurred background filling the letterbox
 * areas. The window's content area already excludes the status / navigation
 * bars, so the fitted content never overlaps them.
 */
public class BoxAspectFrameLayout extends FrameLayout {

    /** Reference box ratio: 1920x1080. */
    private static final float REF_ASPECT = 16f / 9f;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    public BoxAspectFrameLayout(Context context) {
        super(context);
    }

    public BoxAspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setInsets(int left, int top, int right, int bottom) {
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
        requestLayout();
    }

    private int contentWidth(int totalW, int totalH) {
        int availW = Math.max(1, totalW - insetLeft - insetRight);
        int availH = Math.max(1, totalH - insetTop - insetBottom);
        if (availW / (float) availH > REF_ASPECT) {
            return Math.round(availH * REF_ASPECT);
        }
        return availW;
    }

    private int contentHeight(int totalW, int totalH) {
        int availW = Math.max(1, totalW - insetLeft - insetRight);
        int availH = Math.max(1, totalH - insetTop - insetBottom);
        if (availW / (float) availH > REF_ASPECT) {
            return availH;
        }
        return Math.round(availW / REF_ASPECT);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int totalW = MeasureSpec.getSize(widthMeasureSpec);
        int totalH = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(totalW, totalH);
        int cw = contentWidth(totalW, totalH);
        int ch = contentHeight(totalW, totalH);
        int specW = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY);
        int specH = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            measureChild(getChildAt(i), specW, specH);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int totalW = right - left;
        int totalH = bottom - top;
        int availW = Math.max(1, totalW - insetLeft - insetRight);
        int availH = Math.max(1, totalH - insetTop - insetBottom);
        int cw = contentWidth(totalW, totalH);
        int ch = contentHeight(totalW, totalH);
        int cx = insetLeft + (availW - cw) / 2;
        int cy = insetTop + (availH - ch) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(cx, cy, cx + cw, cy + ch);
        }
    }
}
