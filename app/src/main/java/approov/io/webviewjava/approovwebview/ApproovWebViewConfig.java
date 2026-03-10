package approov.io.webviewjava.approovwebview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable configuration for the reusable Approov WebView bridge.
 *
 * <p>The key inputs are:
 * <ul>
 *   <li>The Approov initial configuration string.
 *   <li>The header name used for the JWT, for example {@code approov-token}.
 *   <li>The trusted page origins that are allowed to talk to the native bridge.
 *   <li>Optional secret headers that must be injected on matching outbound requests.
 * </ul>
 *
 * <p>Only pages served from {@link #getAllowedOriginRules()} can access the bridge. That keeps the
 * trust boundary explicit when the helper is reused in a larger application.
 */
public final class ApproovWebViewConfig {
    private final String approovConfig;
    private final String approovDevKey;
    private final String approovTokenHeaderName;
    private final boolean allowRequestsWithoutApproov;
    private final Set<String> allowedOriginRules;
    private final List<ApproovWebViewSecretHeader> secretHeaders;

    private ApproovWebViewConfig(Builder builder) {
        approovConfig = builder.approovConfig == null ? "" : builder.approovConfig.trim();
        approovDevKey = builder.approovDevKey == null ? "" : builder.approovDevKey.trim();
        approovTokenHeaderName = builder.approovTokenHeaderName;
        allowRequestsWithoutApproov = builder.allowRequestsWithoutApproov;
        allowedOriginRules = Collections.unmodifiableSet(new LinkedHashSet<>(builder.allowedOriginRules));
        secretHeaders = Collections.unmodifiableList(new ArrayList<>(builder.secretHeaders));
    }

    public String getApproovConfig() {
        return approovConfig;
    }

    public String getApproovDevKey() {
        return approovDevKey;
    }

    public String getApproovTokenHeaderName() {
        return approovTokenHeaderName;
    }

    public boolean allowsRequestsWithoutApproov() {
        return allowRequestsWithoutApproov;
    }

    public Set<String> getAllowedOriginRules() {
        return allowedOriginRules;
    }

    public List<ApproovWebViewSecretHeader> getSecretHeaders() {
        return secretHeaders;
    }

    public static final class Builder {
        private final String approovConfig;
        private String approovDevKey = "";
        private String approovTokenHeaderName = "approov-token";
        private boolean allowRequestsWithoutApproov = true;
        private final Set<String> allowedOriginRules = new LinkedHashSet<>();
        private final List<ApproovWebViewSecretHeader> secretHeaders = new ArrayList<>();

        public Builder(String approovConfig) {
            this.approovConfig = approovConfig;
        }

        public Builder setApproovDevKey(String approovDevKey) {
            this.approovDevKey = approovDevKey == null ? "" : approovDevKey;
            return this;
        }

        public Builder setApproovTokenHeaderName(String approovTokenHeaderName) {
            if (approovTokenHeaderName == null || approovTokenHeaderName.isBlank()) {
                throw new IllegalArgumentException("approovTokenHeaderName must not be blank");
            }

            this.approovTokenHeaderName = approovTokenHeaderName;
            return this;
        }

        /**
         * Controls whether requests may proceed without Approov protection when initialization or
         * Approov-side networking fails. Defaults to {@code true} so the sample is fail-open.
         */
        public Builder setAllowRequestsWithoutApproov(boolean allowRequestsWithoutApproov) {
            this.allowRequestsWithoutApproov = allowRequestsWithoutApproov;
            return this;
        }

        /**
         * Adds a trusted page origin that can use the injected bridge.
         *
         * <p>Examples:
         * <ul>
         *   <li>{@code https://appassets.androidplatform.net}
         *   <li>{@code https://example.com}
         *   <li>{@code https://*.example.com}
         * </ul>
         */
        public Builder addAllowedOriginRule(String allowedOriginRule) {
            if (allowedOriginRule == null || allowedOriginRule.isBlank()) {
                throw new IllegalArgumentException("allowedOriginRule must not be blank");
            }

            allowedOriginRules.add(allowedOriginRule);
            return this;
        }

        public Builder addSecretHeader(ApproovWebViewSecretHeader secretHeader) {
            if (secretHeader == null) {
                throw new IllegalArgumentException("secretHeader must not be null");
            }

            secretHeaders.add(secretHeader);
            return this;
        }

        public ApproovWebViewConfig build() {
            if (allowedOriginRules.isEmpty()) {
                throw new IllegalStateException(
                    "At least one allowed origin rule is required so the WebView trust boundary stays explicit."
                );
            }

            return new ApproovWebViewConfig(this);
        }
    }
}
