package com.airachabanda.app.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

enum class AdaptiveWidth {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

enum class SupportPane(val label: String) {
    CONVERSATION("Conversation"),
    PARTICIPANTS("Participants"),
    RESEARCH("Research"),
    EVIDENCE("Evidence"),
    OBJECTIONS("Objections"),
    ATTACHMENTS("Attachments"),
    DOCUMENTS("Documents"),
    TECHNICAL("Technical"),
}

data class FoldGuide(
    val reported: Boolean,
    val separating: Boolean,
    val occluding: Boolean,
    val halfOpened: Boolean,
    val vertical: Boolean,
    val startDp: Int,
    val endDp: Int,
    val label: String,
) {
    val gapDp: Int
        get() = if (separating || occluding) (endDp - startDp).coerceAtLeast(0) else 0

    val tabletop: Boolean
        get() = reported && halfOpened && !vertical && gapDp > 0

    val book: Boolean
        get() = reported && halfOpened && vertical && gapDp > 0

    companion object {
        val NONE = FoldGuide(
            reported = false,
            separating = false,
            occluding = false,
            halfOpened = false,
            vertical = false,
            startDp = 0,
            endDp = 0,
            label = "Folding posture was not reported. The layout uses the window width.",
        )
    }
}

object AdaptiveLayout {
    fun widthFromDp(widthDp: Int): AdaptiveWidth = when {
        widthDp < 600 -> AdaptiveWidth.COMPACT
        widthDp < 840 -> AdaptiveWidth.MEDIUM
        else -> AdaptiveWidth.EXPANDED
    }

    fun foldGuide(
        reported: Boolean,
        separating: Boolean,
        occluding: Boolean,
        halfOpened: Boolean,
        vertical: Boolean,
        startPx: Int,
        endPx: Int,
        density: Float,
    ): FoldGuide {
        if (!reported || density <= 0f) return FoldGuide.NONE
        val start = (startPx / density).toInt().coerceAtLeast(0)
        val end = (endPx / density).toInt().coerceAtLeast(start)
        val gap = if (separating || occluding) end - start else 0
        val label = when {
            halfOpened && !vertical && gap > 0 -> "Tabletop posture. Controls stay off the hinge."
            halfOpened && vertical && gap > 0 -> "Book posture. The hinge separates the panes."
            gap > 0 && vertical -> "A vertical fold separates the panes."
            gap > 0 -> "A horizontal fold separates the panes."
            else -> "A fold was reported and it does not separate the display."
        }
        return FoldGuide(true, separating, occluding, halfOpened, vertical, start, end, label)
    }

    fun controlsAvoidGap(controlStart: Int, controlEnd: Int, gapStart: Int, gapEnd: Int): Boolean {
        if (gapEnd <= gapStart) return true
        return controlEnd <= gapStart || controlStart >= gapEnd
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberAdaptiveWidth(): AdaptiveWidth {
    val activity = LocalActivity.current ?: return AdaptiveWidth.COMPACT
    val width = calculateWindowSizeClass(activity).widthSizeClass
    return when (width) {
        WindowWidthSizeClass.Compact -> AdaptiveWidth.COMPACT
        WindowWidthSizeClass.Medium -> AdaptiveWidth.MEDIUM
        else -> AdaptiveWidth.EXPANDED
    }
}

@Composable
fun rememberFoldGuide(): FoldGuide {
    val activity = LocalActivity.current ?: return FoldGuide.NONE
    val layoutInfo by remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }.collectAsState(initial = null)
    val info = layoutInfo ?: return FoldGuide.NONE
    val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return FoldGuide.NONE.copy(
        reported = true,
        label = "No fold feature was reported. The layout uses the window width.",
    )
    val density = LocalDensity.current.density
    val bounds = feature.bounds
    val vertical = feature.orientation == FoldingFeature.Orientation.VERTICAL
    return AdaptiveLayout.foldGuide(
        reported = true,
        separating = feature.isSeparating,
        occluding = feature.occlusionType == FoldingFeature.OcclusionType.FULL,
        halfOpened = feature.state == FoldingFeature.State.HALF_OPENED,
        vertical = vertical,
        startPx = if (vertical) bounds.left else bounds.top,
        endPx = if (vertical) bounds.right else bounds.bottom,
        density = density,
    )
}
