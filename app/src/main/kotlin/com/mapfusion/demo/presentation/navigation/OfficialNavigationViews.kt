package com.mapfusion.demo.presentation.navigation

import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mapfusion.api.model.OfficialNavigationMode
import com.mapfusion.api.model.TravelMode

/** 页面内的控件引用表，仅供构造层与渲染层共享；不存业务状态，也不拥有导航引擎。 */
internal class OfficialNavigationViews {
    lateinit var navigatorHost: FrameLayout
    lateinit var placeholder: TextView
    lateinit var stateBadge: TextView
    lateinit var detail: TextView
    lateinit var speedRow: LinearLayout
    lateinit var speedValue: TextView
    lateinit var speed: SeekBar
    val modes = linkedMapOf<OfficialNavigationMode, Button>()
    val travelModes = linkedMapOf<TravelMode, Button>()
    val actions = linkedMapOf<OfficialNavigationAction, Button>()
}
