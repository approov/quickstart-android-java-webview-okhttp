package approov.io.webviewjava;

import android.app.Application;

import approov.io.webviewjava.approovwebview.ApproovWebViewConfig;
import approov.io.webviewjava.approovwebview.ApproovWebViewSecretHeader;
import approov.io.webviewjava.approovwebview.ApproovWebViewSupport;

/**
 * Sample quickstart application entry point.
 *
 * <p>This class deliberately keeps the app-specific wiring tiny. The reusable logic lives in
 * {@code approovwebview/ApproovWebViewSupport}. Host applications only need to describe their
 * Approov setup and any native-only secret headers here.
 */
public class WebViewJavaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        ApproovWebViewConfig config = new ApproovWebViewConfig.Builder(BuildConfig.APPROOV_CONFIG)
            .setApproovDevKey(BuildConfig.APPROOV_DEV_KEY)
            .setApproovTokenHeaderName("approov-token")
            .setAllowRequestsWithoutApproov(true)
            // Only pages loaded from this HTTPS asset origin can use the bridge in the quickstart.
            .addAllowedOriginRule(ApproovWebViewSupport.LOCAL_ASSET_ORIGIN)
            // The sample API still needs its static api-key, but the WebView page should never see it.
            .addSecretHeader(new ApproovWebViewSecretHeader(
                "shapes.approov.io",
                "/v2/",
                "api-key",
                BuildConfig.SHAPES_API_KEY
            ))
            .build();

        ApproovWebViewSupport.initialize(this, config);
    }
}
