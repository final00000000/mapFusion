package com.mapfusion.demo

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** 仅用于让真机契约测试在“仅使用期间”位置权限下保持前台。 */
class DeviceTestHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(View(this))
    }
}
