package approov.io.webviewjava.approovwebview;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import approov.io.webviewjava.BuildConfig;
import io.approov.service.okhttp.ApproovService;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reusable helper that turns a normal Android {@link WebView} into an "Approov WebView".
 *
 * <p>The helper owns four generic responsibilities:
 * <ul>
 *   <li>Initialize the Approov Android SDK once at process startup.
 *   <li>Expose a scoped JavaScript bridge to trusted page origins only.
 *   <li>Execute WebView network calls with OkHttp wrapped by {@link ApproovService}.
 *   <li>Inject extra secret headers such as API keys natively, instead of exposing them to JS.
 * </ul>
 *
 * <p>The sample app keeps its Approov-specific code limited to:
 * <ul>
 *   <li>Building an {@link ApproovWebViewConfig} in {@code Application#onCreate()}.
 *   <li>Calling {@link #configureWebView(WebView)} in the activity.
 *   <li>Loading the sample page URL returned by {@link #getAssetUrl(String)}.
 * </ul>
 *
 * <p>When you reuse this class in another app, most of your work happens in the config object.
 */
public final class ApproovWebViewSupport {
    public static final String LOCAL_ASSET_ORIGIN = "https://appassets.androidplatform.net";
    private static final String TAG = "ApproovWebView";
    private static final String BRIDGE_OBJECT_NAME = "ApproovNativeBridge";
    private static final String BRIDGE_SCRIPT_ASSET = "approov-webview-bridge.js";
    private static final String HEADER_CONTENT_TYPE = "content-type";

    private static volatile ApproovWebViewSupport instance;

    private final Application application;
    private final ApproovWebViewConfig config;
    private final WebViewAssetLoader assetLoader;
    private final String bridgeScript;
    private final ExecutorService requestExecutor;
    private final Handler mainHandler;
    private final Map<WebView, Boolean> preparedWebViews =
        Collections.synchronizedMap(new WeakHashMap<>());

    private volatile boolean approovInitialized = false;
    private volatile boolean fallbackWarningLogged = false;
    private volatile String initializationError = "Approov has not been initialized yet.";
    private volatile OkHttpClient okHttpClient;
    private volatile OkHttpClient fallbackOkHttpClient;

    private ApproovWebViewSupport(Application application, ApproovWebViewConfig config) {
        this.application = application;
        this.config = config;
        this.assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(application))
            .build();
        this.bridgeScript = readAssetText(BRIDGE_SCRIPT_ASSET);
        this.requestExecutor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());

        initializeApproov();
    }

    /**
     * Initializes the singleton helper. Call once from {@code Application#onCreate()}.
     */
    public static synchronized void initialize(Application application, ApproovWebViewConfig config) {
        instance = new ApproovWebViewSupport(application, config);
    }

    public static ApproovWebViewSupport getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                "ApproovWebViewSupport has not been initialized. Call initialize() from Application.onCreate()."
            );
        }

        return instance;
    }

    /**
     * Configures the WebView with safe defaults and attaches the Approov message bridge.
     *
     * <p>This method is intentionally generic: it does not know anything about the sample Shapes API.
     * Any trusted page that runs inside the WebView can now use ordinary {@code fetch()} or
     * {@code XMLHttpRequest}, and the helper will execute those calls with Approov-protected OkHttp.
     */
    public void configureWebView(WebView webView) {
        requireFeature(WebViewFeature.WEB_MESSAGE_LISTENER, "WEB_MESSAGE_LISTENER");

        configureSettings(webView.getSettings());
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        if (preparedWebViews.put(webView, Boolean.TRUE) != null) {
            return;
        }

        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_OBJECT_NAME,
            config.getAllowedOriginRules(),
            (view, message, sourceOrigin, isMainFrame, replyProxy) ->
                handleWebMessage(view, message, sourceOrigin, isMainFrame, replyProxy)
        );

        // Document-start injection is the cleanest option because the page gets the wrapped fetch/XHR
        // before its own JavaScript executes. When the feature is missing we log a warning, and the
        // sample page still works because it also includes the bridge asset manually as a fallback.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            ScriptHandler scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                bridgeScript,
                config.getAllowedOriginRules()
            );

            // The script handler is intentionally not stored. The bridge should live for the lifetime
            // of the WebView, and the WebView is destroyed by the hosting activity.
            if (scriptHandler == null) {
                Log.w(TAG, "Document-start script injection returned a null handler.");
            }
        } else {
            Log.w(
                TAG,
                "DOCUMENT_START_SCRIPT is unavailable. Include approov-webview-bridge.js manually before page code."
            );
        }
    }

    /**
     * Returns a client that serves local assets from {@code https://appassets.androidplatform.net}.
     *
     * <p>Using {@link WebViewAssetLoader} avoids the older {@code file:///android_asset/...} model and
     * gives the local quickstart page a normal HTTPS origin. That origin can then be allow-listed for
     * the bridge using {@link ApproovWebViewConfig.Builder#addAllowedOriginRule(String)}.
     */
    public WebViewClient buildWebViewClient(WebViewClient delegate) {
        return new AssetLoadingWebViewClient(assetLoader, delegate);
    }

    /**
     * Builds a URL for a file in {@code app/src/main/assets}.
     */
    public String getAssetUrl(String assetPath) {
        String normalizedPath = assetPath == null ? "" : assetPath.replaceFirst("^/+", "");
        return LOCAL_ASSET_ORIGIN + "/assets/" + normalizedPath;
    }

    public String getInitializationError() {
        return initializationError;
    }

    private void initializeApproov() {
        OkHttpClient.Builder baseClientBuilder = buildBaseOkHttpClientBuilder();
        fallbackOkHttpClient = baseClientBuilder.build();

        if (config.getApproovConfig().isBlank()) {
            initializationError =
                "Approov is not configured. Add approov.config to local.properties or pass -PapproovConfig.";
            logInitializationWarning(initializationError);
            return;
        }

        try {
            ApproovService.initialize(application, config.getApproovConfig());
            ApproovService.setOkHttpClientBuilder(baseClientBuilder);
            ApproovService.setProceedOnNetworkFail(config.allowsRequestsWithoutApproov());
            ApproovService.setApproovHeader(config.getApproovTokenHeaderName(), "");

            if (!config.getApproovDevKey().isBlank()) {
                ApproovService.setDevKey(config.getApproovDevKey());
            }

            approovInitialized = true;
            initializationError = "";
        } catch (Exception exception) {
            initializationError = "Approov initialization failed: "
                + (exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage());
            if (config.allowsRequestsWithoutApproov()) {
                Log.w(TAG, initializationError + ". Requests will proceed without Approov protection.", exception);
            } else {
                Log.e(TAG, "Approov initialization failed", exception);
            }
        }
    }

    private void handleWebMessage(
        WebView view,
        WebMessageCompat message,
        Uri sourceOrigin,
        boolean isMainFrame,
        JavaScriptReplyProxy replyProxy
    ) {
        final String rawRequest = message == null ? null : message.getData();

        requestExecutor.execute(() -> {
            String reply = handleRequestMessage(rawRequest, sourceOrigin, isMainFrame);
            view.post(() -> replyProxy.postMessage(reply));
        });
    }

    private String handleRequestMessage(String rawRequest, Uri sourceOrigin, boolean isMainFrame) {
        JSONObject envelope = new JSONObject();

        try {
            JSONObject request = new JSONObject(rawRequest == null ? "{}" : rawRequest);
            String requestId = request.optString("requestId", "");

            envelope.put("requestId", requestId);
            envelope.put("status", "success");
            envelope.put("payload", executeRequest(request, sourceOrigin, isMainFrame));
        } catch (Exception exception) {
            try {
                envelope.put("status", "error");
                envelope.put("error", buildErrorPayload(exception));
            } catch (JSONException jsonException) {
                Log.e(TAG, "Failed to serialize bridge error", jsonException);
            }
        }

        return envelope.toString();
    }

    private JSONObject executeRequest(
        JSONObject webRequest,
        Uri sourceOrigin,
        boolean isMainFrame
    ) throws Exception {
        String url = webRequest.getString("url");
        String method = webRequest.optString("method", "GET").toUpperCase(Locale.US);
        JSONObject requestHeaders = webRequest.optJSONObject("headers");
        String requestBodyText = webRequest.has("body") && !webRequest.isNull("body")
            ? webRequest.getString("body")
            : null;

        URI requestUri = URI.create(url);
        Request.Builder requestBuilder = new Request.Builder().url(url);

        applyHeaders(requestBuilder, requestHeaders);
        applySecretHeaders(requestBuilder, requestHeaders, requestUri);
        requestBuilder.method(method, buildRequestBody(method, requestBodyText, requestHeaders));

        // Logging the page origin here is useful when the helper is reused in a larger application.
        // If a request shows up from an unexpected page origin, adjust the allowed origin rules rather
        // than widening the bridge globally.
        Log.d(
            TAG,
            "Executing bridged request from origin="
                + sourceOrigin
                + ", mainFrame="
                + isMainFrame
                + ", url="
                + url
        );

        try (Response response = getNetworkClient().newCall(requestBuilder.build()).execute()) {
            return buildResponsePayload(response);
        }
    }

    private OkHttpClient getNetworkClient() {
        if (!approovInitialized) {
            if (config.allowsRequestsWithoutApproov()) {
                if (!fallbackWarningLogged) {
                    fallbackWarningLogged = true;
                    Log.w(TAG, "Proceeding without Approov protection: " + initializationError);
                }
                return fallbackOkHttpClient;
            }

            throw new IllegalStateException(initializationError);
        }

        OkHttpClient client = okHttpClient;
        if (client != null) {
            return client;
        }

        synchronized (this) {
            if (okHttpClient == null) {
                okHttpClient = ApproovService.getOkHttpClient();
            }

            return okHttpClient;
        }
    }

    private OkHttpClient.Builder buildBaseOkHttpClientBuilder() {
        return new OkHttpClient.Builder().addNetworkInterceptor(chain -> {
            Request requestWithCookies = injectWebViewCookies(chain.request());
            Response response = chain.proceed(requestWithCookies);
            storeResponseCookies(response);
            return response;
        });
    }

    private Request injectWebViewCookies(Request request) {
        if (request.header("Cookie") != null) {
            return request;
        }

        String cookieHeader = callOnMainThread(() -> CookieManager.getInstance().getCookie(request.url().toString()));
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return request;
        }

        return request.newBuilder().header("Cookie", cookieHeader).build();
    }

    private void storeResponseCookies(Response response) {
        List<String> setCookieHeaders = response.headers("Set-Cookie");
        if (setCookieHeaders.isEmpty()) {
            return;
        }

        String url = response.request().url().toString();
        runOnMainThread(() -> {
            CookieManager cookieManager = CookieManager.getInstance();
            for (String cookieValue : setCookieHeaders) {
                cookieManager.setCookie(url, cookieValue);
            }
            cookieManager.flush();
        });
    }

    private void configureSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        runOnMainThread(() -> CookieManager.getInstance().setAcceptCookie(true));
    }

    private void applyHeaders(Request.Builder requestBuilder, JSONObject requestHeaders) throws JSONException {
        if (requestHeaders == null) {
            return;
        }

        Iterator<String> headerNames = requestHeaders.keys();
        while (headerNames.hasNext()) {
            String headerName = headerNames.next();
            requestBuilder.header(headerName, requestHeaders.optString(headerName, ""));
        }
    }

    private void applySecretHeaders(
        Request.Builder requestBuilder,
        JSONObject requestHeaders,
        URI requestUri
    ) {
        for (ApproovWebViewSecretHeader secretHeader : config.getSecretHeaders()) {
            if (secretHeader.matches(requestUri) && !hasHeader(requestHeaders, secretHeader.getHeaderName())) {
                requestBuilder.header(secretHeader.getHeaderName(), secretHeader.getHeaderValue());
            }
        }
    }

    private boolean hasHeader(JSONObject headersObject, String expectedHeaderName) {
        if (headersObject == null) {
            return false;
        }

        Iterator<String> headerNames = headersObject.keys();
        while (headerNames.hasNext()) {
            String headerName = headerNames.next();
            if (expectedHeaderName.equalsIgnoreCase(headerName)) {
                return true;
            }
        }

        return false;
    }

    private RequestBody buildRequestBody(String method, String requestBodyText, JSONObject headersObject) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return null;
        }

        String contentTypeValue = findHeaderValue(headersObject, HEADER_CONTENT_TYPE);
        MediaType mediaType = contentTypeValue == null || contentTypeValue.isBlank()
            ? null
            : MediaType.parse(contentTypeValue);

        return RequestBody.create(requestBodyText == null ? "" : requestBodyText, mediaType);
    }

    private String findHeaderValue(JSONObject headersObject, String expectedHeaderName) {
        if (headersObject == null) {
            return null;
        }

        Iterator<String> headerNames = headersObject.keys();
        while (headerNames.hasNext()) {
            String headerName = headerNames.next();
            if (expectedHeaderName.equalsIgnoreCase(headerName)) {
                return headersObject.optString(headerName, null);
            }
        }

        return null;
    }

    private JSONObject buildResponsePayload(Response response) throws IOException, JSONException {
        JSONObject payload = new JSONObject();
        payload.put("ok", response.isSuccessful());
        payload.put("status", response.code());
        payload.put("statusText", response.message());
        payload.put("url", response.request().url().toString());
        payload.put("headers", flattenHeaders(response.headers()));

        ResponseBody responseBody = response.body();
        payload.put("bodyText", responseBody == null ? "" : responseBody.string());
        return payload;
    }

    private JSONObject buildErrorPayload(Exception exception) throws JSONException {
        JSONObject errorPayload = new JSONObject();
        errorPayload.put(
            "message",
            exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
        );
        errorPayload.put("type", exception.getClass().getSimpleName());
        return errorPayload;
    }

    private JSONObject flattenHeaders(Headers headers) throws JSONException {
        JSONObject flattenedHeaders = new JSONObject();
        for (String name : headers.names()) {
            flattenedHeaders.put(name, headers.values(name).size() == 1
                ? headers.get(name)
                : String.join(", ", headers.values(name)));
        }
        return flattenedHeaders;
    }

    private void requireFeature(String featureName, String humanName) {
        if (!WebViewFeature.isFeatureSupported(featureName)) {
            throw new IllegalStateException(
                humanName + " is not supported by this WebView provider. Update Android System WebView or Chrome."
            );
        }
    }

    private String readAssetText(String assetPath) {
        try (
            InputStream inputStream = application.getAssets().open(assetPath);
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader)
        ) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bridge asset " + assetPath, exception);
        }
    }

    private void logInitializationWarning(String message) {
        if (config.allowsRequestsWithoutApproov()) {
            Log.w(TAG, message + ". Requests will proceed without Approov protection.");
        } else {
            Log.w(TAG, message);
        }
    }

    private <T> T callOnMainThread(MainThreadValueSupplier<T> supplier) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return supplier.get();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                result.set(supplier.get());
            } catch (RuntimeException exception) {
                error.set(exception);
            } finally {
                latch.countDown();
            }
        });

        awaitMainThreadTask(latch);

        if (error.get() != null) {
            throw error.get();
        }

        return result.get();
    }

    private void runOnMainThread(MainThreadRunnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                runnable.run();
            } catch (RuntimeException exception) {
                error.set(exception);
            } finally {
                latch.countDown();
            }
        });

        awaitMainThreadTask(latch);

        if (error.get() != null) {
            throw error.get();
        }
    }

    private void awaitMainThreadTask(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while synchronizing cookies with WebView.", exception);
        }
    }

    @FunctionalInterface
    private interface MainThreadRunnable {
        void run();
    }

    @FunctionalInterface
    private interface MainThreadValueSupplier<T> {
        T get();
    }

    /**
     * Minimal wrapper client that keeps asset loading generic while still letting host apps delegate
     * their own navigation callbacks.
     */
    private static final class AssetLoadingWebViewClient extends WebViewClientCompat {
        private final WebViewAssetLoader assetLoader;
        private final WebViewClient delegate;

        private AssetLoadingWebViewClient(WebViewAssetLoader assetLoader, WebViewClient delegate) {
            this.assetLoader = assetLoader;
            this.delegate = delegate;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
            if (response != null) {
                return response;
            }

            return delegate == null ? super.shouldInterceptRequest(view, request) : delegate.shouldInterceptRequest(view, request);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            WebResourceResponse response = assetLoader.shouldInterceptRequest(Uri.parse(url));
            if (response != null) {
                return response;
            }

            return delegate == null ? super.shouldInterceptRequest(view, url) : delegate.shouldInterceptRequest(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return delegate != null && delegate.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (delegate != null) {
                delegate.onPageStarted(view, url, favicon);
            } else {
                super.onPageStarted(view, url, favicon);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (delegate != null) {
                delegate.onPageFinished(view, url);
            } else {
                super.onPageFinished(view, url);
            }
        }

    }
}
