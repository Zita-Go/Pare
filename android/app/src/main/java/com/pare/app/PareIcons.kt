package com.pare.app

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

/**
 * Bundled Material Symbols Rounded: weight 400, grade 0, optical size 24.
 * Navigation uses the matching fill-1 asset when selected.
 * Source and license: android/third_party/material-symbols.
 */
internal object PareIcons {
    val Today: Int = R.drawable.pare_symbol_event_available
    val TodaySelected: Int = R.drawable.pare_symbol_event_available_filled
    val Quadrants: Int = R.drawable.pare_symbol_grid_view
    val QuadrantsSelected: Int = R.drawable.pare_symbol_grid_view_filled
    val Idea: Int = R.drawable.pare_symbol_lightbulb
    val IdeaSelected: Int = R.drawable.pare_symbol_lightbulb_filled
    val Calendar: Int = R.drawable.pare_symbol_calendar_month
    val Play: Int = R.drawable.pare_symbol_play_circle
    val Pause: Int = R.drawable.pare_symbol_pause_circle
    val Back: Int = R.drawable.pare_symbol_arrow_back
    val Next: Int = R.drawable.pare_symbol_chevron_right
    val Previous: Int = R.drawable.pare_symbol_chevron_left
    val Down: Int = R.drawable.pare_symbol_expand_more
    val Up: Int = R.drawable.pare_symbol_expand_less
    val Close: Int = R.drawable.pare_symbol_close
    val More: Int = R.drawable.pare_symbol_more_vert
    val Edit: Int = R.drawable.pare_symbol_edit
    val Add: Int = R.drawable.pare_symbol_add
    val Check: Int = R.drawable.pare_symbol_check
    val Star: Int = R.drawable.pare_symbol_star
    val Sparkle: Int = R.drawable.pare_symbol_auto_awesome
    val Note: Int = R.drawable.pare_symbol_description
    val Decision: Int = R.drawable.pare_symbol_flag
    val Link: Int = R.drawable.pare_symbol_link
    val Unlink: Int = R.drawable.pare_symbol_link_off
    val Archive: Int = R.drawable.pare_symbol_archive
    val Refresh: Int = R.drawable.pare_symbol_refresh
    val CheckCircle: Int = R.drawable.pare_symbol_check_circle
    val Clock: Int = R.drawable.pare_symbol_schedule
    val Seed: Int = R.drawable.pare_symbol_eco

    val Finding: Int = Idea
}

/** Decorative icons use a null description; the owning control supplies its accessible label. */
@Composable
internal fun PareIcon(
    @DrawableRes id: Int,
    description: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(id),
        contentDescription = description,
        modifier = modifier,
        tint = tint,
    )
}
