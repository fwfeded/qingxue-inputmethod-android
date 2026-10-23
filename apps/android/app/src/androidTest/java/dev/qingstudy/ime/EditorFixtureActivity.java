package dev.qingstudy.ime;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

/** 独立测试包中的原生输入框；不依赖目标 APK 中的 Kotlin 运行库。 */
public class EditorFixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 60, 24, 0);
        for (String label : new String[]{"外部文本框一", "外部文本框二", "外部密码框"}) {
            EditText editor = new EditText(this);
            editor.setContentDescription(label);
            editor.setHint(label);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | (label.equals("外部密码框")
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
            panel.addView(editor, new LinearLayout.LayoutParams(-1, 100));
        }
        setContentView(panel);
    }
}
