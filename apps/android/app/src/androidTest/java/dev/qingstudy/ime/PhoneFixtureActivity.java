package dev.qingstudy.ime;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 手机布局测试页：独立应用中的文本、搜索、数字及电话输入框。 */
public class PhoneFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                pad + insets.getSystemWindowInsetRight(), pad);
            return insets;
        });
        String mode = getIntent().getStringExtra("mode");
        TextView result = new TextView(this);
        result.setText("手机输入验证 " + mode);
        EditText editor = new EditText(this);
        editor.setId(1001);
        editor.setContentDescription("手机测试输入框");
        editor.setHint("手机测试输入框");
        if (getIntent().getBooleanExtra("hideHint", false)) editor.setHint("");
        editor.setTextSize(20);
        int type = InputType.TYPE_CLASS_TEXT;
        if ("number".equals(mode)) type = InputType.TYPE_CLASS_NUMBER;
        if ("phone".equals(mode)) type = InputType.TYPE_CLASS_PHONE;
        if ("password".equals(mode)) type |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        editor.setInputType(type);
        editor.setImeOptions("search".equals(mode) ? EditorInfo.IME_ACTION_SEARCH : EditorInfo.IME_ACTION_DONE);
        if ("send".equals(mode)) editor.setImeOptions(EditorInfo.IME_ACTION_SEND);
        if ("private".equals(mode)) editor.setImeOptions(editor.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        editor.setOnEditorActionListener((view, action, event) -> {
            result.setText("已触发动作 " + action);
            return true;
        });
        panel.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(result);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        setContentView(scroll);
    }
}
