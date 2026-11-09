package com.mapfusion.demo

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** 仅用于让真机契约测试在“仅使用期间”位置权限下保持前台。 */
class DeviceTestHostActivity : Activity() {

    /** 保持契约测试宿主前台及屏幕常亮，不创建地图或改变用户隐私决定。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(View(this))
    }
}
