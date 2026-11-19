package app.landrop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** شاشة اختيار طريقة النقل: واي فاي / Nearby / بلوتوث. */
public class ChooserActivity extends Activity {
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void option(LinearLayout root, String title, String desc, Runnable go) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setOnClickListener(v -> go.run());
        root.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(this);
        t.setText(desc);
        t.setTextColor(Color.GRAY);
        t.setTextSize(13);
        t.setPadding(dp(4), dp(2), dp(4), dp(16));
        root.addView(t);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView h = new TextView(this);
        h.setText("📡 LAN Drop\nاختار طريقة النقل");
        h.setTextSize(22);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, 0, 0, dp(24));
        root.addView(h);

        option(root, "📶 واي فاي",
                "الأسرع. على نفس الشبكة أو Hotspot، ويشتغل مع اللابتوب وأي متصفح.",
                () -> startActivity(new Intent(this, MainActivity.class)));
        option(root, "📡 Nearby (زي AirDrop)",
                "من غير راوتر ولا نت. تليفونين أندرويد قريبين من بعض، بيستخدم بلوتوث وWi-Fi Direct تلقائيًا (محتاج Google Play Services).",
                () -> startActivity(new Intent(this, P2PActivity.class).putExtra(P2PActivity.EXTRA_MODE, "nearby")));
        option(root, "🔵 بلوتوث",
                "من غير شبكة ومن غير Google Play Services، بس بطيء (مناسب للملفات الصغيرة).",
                () -> startActivity(new Intent(this, P2PActivity.class).putExtra(P2PActivity.EXTRA_MODE, "bt")));
        setContentView(root);
    }
}
