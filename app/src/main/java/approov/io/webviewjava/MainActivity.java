package approov.io.webviewjava;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import approov.io.webviewjava.approovwebview.ApproovWebViewSupport;

/**
 * Thin sample activity that shows how little app code is needed after the Approov WebView bridge
 * has been encapsulated into the reusable helper package.
 */
public class MainActivity extends AppCompatActivity {
    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        ApproovWebViewSupport approovWebViewSupport = ApproovWebViewSupport.getInstance();
        approovWebViewSupport.configureWebView(webView);
        webView.setWebViewClient(approovWebViewSupport.buildWebViewClient(null));
        webView.loadUrl(approovWebViewSupport.getAssetUrl("index.html"));
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");

            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }

            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
