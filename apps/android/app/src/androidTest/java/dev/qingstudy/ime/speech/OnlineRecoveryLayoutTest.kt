//! 窄屏大字体下断线说明必须完整可见，同时底部恢复操作留在键盘内。
package dev.qingstudy.ime

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class OnlineRecoveryLayoutTest {
    @Test fun completeFailureMessageFitsAtLargeFont() {
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val base=inst.targetContext
            val config=Configuration(base.resources.configuration).apply { fontScale=1.3f }
            val context=ContextThemeWrapper(base.createConfigurationContext(config),android.R.style.Theme_Material_Light_NoActionBar)
            val panel=VoicePanelView(context) { _,_-> }
            panel.mode(false,"换行")
            panel.update("临时文字",OnlineVoiceConfig.message("network"),false,false);panel.recovery()
            val d=context.resources.displayMetrics.density
            panel.measure(View.MeasureSpec.makeMeasureSpec((320*d).toInt(),View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((300*d).toInt(),View.MeasureSpec.EXACTLY))
            panel.layout(0,0,panel.measuredWidth,panel.measuredHeight)
            fun find(view:View,desc:String):TextView? {
                if(view is TextView&&view.contentDescription==desc)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i),desc)?.let{return it}
                return null
            }
            val label=find(panel,"语音状态")!!
            assertTrue("断线提示最后一行被裁切",label.layout.getLineBottom(label.lineCount-1)<=label.height-label.compoundPaddingTop-label.compoundPaddingBottom)
            val last=find(panel,"语音 server-settings")!!
            val position=android.graphics.Rect(0,0,last.width,last.height)
            panel.offsetDescendantRectToMyCoords(last,position)
            assertTrue(position.bottom<=panel.height)
        }
    }
}
