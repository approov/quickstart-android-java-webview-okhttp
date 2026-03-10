/* global Request, Response, Headers, URL */

(function () {
  // The bridge can be injected in two ways:
  // 1. Automatically at document-start by ApproovWebViewSupport on modern WebView builds.
  // 2. Manually through a script tag as a fallback.
  // The guard prevents double-installation when both are present.
  if (window.__approovWebViewInstalled) {
    return;
  }
  window.__approovWebViewInstalled = true;

  const BRIDGE_NAME = "ApproovNativeBridge";
  const pendingRequests = new Map();
  const originalFetch = typeof window.fetch === "function" ? window.fetch.bind(window) : null;
  const OriginalXMLHttpRequest = window.XMLHttpRequest;
  const OriginalHTMLFormElement = window.HTMLFormElement;
  const originalFormSubmit = OriginalHTMLFormElement
    && typeof OriginalHTMLFormElement.prototype.submit === "function"
    ? OriginalHTMLFormElement.prototype.submit
    : null;
  let requestCounter = 0;

  function nextRequestId() {
    requestCounter += 1;
    return "approov-" + Date.now() + "-" + requestCounter;
  }

  function getNativeBridge() {
    return window[BRIDGE_NAME] || null;
  }

  function resolveUrl(input) {
    if (typeof input === "string") {
      return new URL(input, window.location.href).toString();
    }

    if (typeof URL !== "undefined" && input instanceof URL) {
      return new URL(input.toString(), window.location.href).toString();
    }

    if (input instanceof Request) {
      return new URL(input.url, window.location.href).toString();
    }

    throw new Error("Unsupported request input type.");
  }

  function shouldRouteToNative(url) {
    try {
      const parsed = new URL(url, window.location.href);
      return parsed.protocol === "http:" || parsed.protocol === "https:";
    } catch (error) {
      return false;
    }
  }

  function headersToObject(headersInit) {
    const normalizedHeaders = {};

    if (!headersInit) {
      return normalizedHeaders;
    }

    new Headers(headersInit).forEach(function (value, key) {
      normalizedHeaders[key] = value;
    });

    return normalizedHeaders;
  }

  function findHeaderValue(headersObject, name) {
    if (!headersObject) {
      return null;
    }

    return Object.keys(headersObject).find(function (headerName) {
      return headerName.toLowerCase() === name.toLowerCase();
    }) || null;
  }

  function isSelfTarget(target) {
    return !target || target === "_self";
  }

  function canSerializeForm(form) {
    return !Array.prototype.some.call(form.elements || [], function (element) {
      return element
        && element.tagName === "INPUT"
        && String(element.type || "").toLowerCase() === "file"
        && element.files
        && element.files.length > 0;
    });
  }

  function appendSubmitter(formData, submitter) {
    if (!submitter || !submitter.name) {
      return;
    }

    formData.append(submitter.name, submitter.value || "");
  }

  function serializeTextPlain(formData) {
    const lines = [];
    formData.forEach(function (value, key) {
      lines.push(key + "=" + value);
    });
    return lines.join("\r\n");
  }

  function normalizeFormMethod(form, submitter) {
    const method = ((submitter && submitter.formMethod) || form.method || "GET").toUpperCase();
    return method === "POST" ? "POST" : "GET";
  }

  function hasSupportedFormMethod(form, submitter) {
    const rawMethod = ((submitter && submitter.formMethod) || form.method || "GET").toUpperCase();
    return rawMethod === "GET" || rawMethod === "POST" || rawMethod === "";
  }

  function normalizeFormEnctype(form, submitter) {
    return ((submitter && submitter.formEnctype) || form.enctype || "application/x-www-form-urlencoded")
      .toLowerCase();
  }

  function buildFormRequest(form, submitter) {
    const action = resolveUrl((submitter && submitter.formAction) || form.action || window.location.href);
    const method = normalizeFormMethod(form, submitter);
    const enctype = normalizeFormEnctype(form, submitter);
    const formData = new FormData(form);
    const headers = {};

    appendSubmitter(formData, submitter);

    if (method === "GET") {
      const requestUrl = new URL(action);
      new URLSearchParams(formData).forEach(function (value, key) {
        requestUrl.searchParams.append(key, value);
      });

      return {
        body: null,
        headers: headers,
        method: "GET",
        url: requestUrl.toString()
      };
    }

    if (enctype === "text/plain") {
      headers["content-type"] = "text/plain;charset=UTF-8";
      return {
        body: serializeTextPlain(formData),
        headers: headers,
        method: method,
        url: action
      };
    }

    headers["content-type"] = "application/x-www-form-urlencoded;charset=UTF-8";
    return {
      body: new URLSearchParams(formData).toString(),
      headers: headers,
      method: method,
      url: action
    };
  }

  function shouldRouteFormToNative(form, submitter) {
    if (!form || !OriginalHTMLFormElement || !(form instanceof OriginalHTMLFormElement)) {
      return false;
    }

    const enctype = normalizeFormEnctype(form, submitter);
    const target = (((submitter && submitter.formTarget) || form.target || "") + "").toLowerCase();
    if (
      !hasSupportedFormMethod(form, submitter)
      || !isSelfTarget(target)
      || enctype === "multipart/form-data"
      || !canSerializeForm(form)
    ) {
      return false;
    }

    return shouldRouteToNative((submitter && submitter.formAction) || form.action || window.location.href);
  }

  function updateLocationForResponse(url) {
    if (!url) {
      return;
    }

    try {
      const nextUrl = new URL(url, window.location.href);
      if (nextUrl.origin === window.location.origin) {
        window.history.replaceState(null, "", nextUrl.toString());
      }
    } catch (error) {
      // Cross-origin history updates are not allowed; the response is still rendered below.
    }
  }

  function renderFormResponse(text, responseUrl) {
    updateLocationForResponse(responseUrl);
    document.open();
    document.write(text);
    document.close();
    installFormSupport();
  }

  function submitFormThroughNative(form, submitter) {
    const request = buildFormRequest(form, submitter);
    return performNativeRequest(request.url, {
      body: request.body,
      headers: request.headers,
      method: request.method
    }).then(function (response) {
      return response.text().then(function (text) {
        renderFormResponse(text, response.url || request.url);
        return response;
      });
    });
  }

  function buildResponseObject(result) {
    if (typeof Response === "function") {
      return new Response(result.bodyText || "", {
        headers: result.headers || {},
        status: result.status,
        statusText: result.statusText || ""
      });
    }

    return {
      ok: !!result.ok,
      status: result.status,
      statusText: result.statusText || "",
      url: result.url || "",
      headers: result.headers || {},
      text: function () {
        return Promise.resolve(result.bodyText || "");
      },
      json: function () {
        return Promise.resolve(JSON.parse(result.bodyText || "null"));
      }
    };
  }

  function handleNativeReply(event) {
    let parsedEnvelope;

    try {
      parsedEnvelope = JSON.parse(event.data);
    } catch (error) {
      return;
    }

    const pending = pendingRequests.get(parsedEnvelope.requestId);
    if (!pending) {
      return;
    }

    pendingRequests.delete(parsedEnvelope.requestId);

    if (parsedEnvelope.status === "success") {
      pending.resolve(buildResponseObject(parsedEnvelope.payload || {}));
      return;
    }

    const errorPayload = parsedEnvelope.error || {};
    const nativeError = new Error(errorPayload.message || "Native request failed.");
    nativeError.name = errorPayload.type || "NativeRequestError";
    pending.reject(nativeError);
  }

  async function buildNativePayload(input, init) {
    const request = new Request(input, init || {});
    const method = (request.method || "GET").toUpperCase();
    const body = method === "GET" || method === "HEAD"
      ? null
      : await request.clone().text();

    return {
      body: body,
      headers: headersToObject(request.headers),
      method: method,
      requestId: nextRequestId(),
      url: resolveUrl(request)
    };
  }

  function performNativeRequest(input, init) {
    return buildNativePayload(input, init).then(function (payload) {
      return new Promise(function (resolve, reject) {
        const nativeBridge = getNativeBridge();
        if (!nativeBridge || typeof nativeBridge.postMessage !== "function") {
          reject(new Error("Approov native bridge is unavailable for this origin."));
          return;
        }

        pendingRequests.set(payload.requestId, {
          reject: reject,
          resolve: resolve
        });

        nativeBridge.postMessage(JSON.stringify(payload));
      });
    });
  }

  const nativeBridge = getNativeBridge();
  if (nativeBridge && typeof nativeBridge === "object") {
    nativeBridge.onmessage = handleNativeReply;
  } else {
    console.warn("Approov WebView bridge is not available. Network calls will fail.");
  }

  if (originalFetch) {
    // This keeps page code simple. The page still writes normal fetch() calls, but the transport
    // path is replaced so native Android code can inject the Approov JWT and any extra secret headers.
    const wrappedFetch = function (input, init) {
      const url = resolveUrl(input);
      if (!shouldRouteToNative(url)) {
        return originalFetch(input, init);
      }

      return performNativeRequest(input, init);
    };

    wrappedFetch.__approovWrapped = true;
    window.fetch = wrappedFetch;
  }

  function handleSubmitEvent(event) {
    const form = event.target;
    if (event.defaultPrevented || !shouldRouteFormToNative(form, event.submitter)) {
      return;
    }

    event.preventDefault();
    submitFormThroughNative(form, event.submitter).catch(function () {
      if (originalFormSubmit) {
        originalFormSubmit.call(form);
      }
    });
  }

  function installFormSupport() {
    if (!document || document.__approovFormSupportInstalled) {
      return;
    }

    document.addEventListener("submit", handleSubmitEvent, true);
    document.__approovFormSupportInstalled = true;
  }

  installFormSupport();

  if (OriginalHTMLFormElement && originalFormSubmit) {
    OriginalHTMLFormElement.prototype.submit = function () {
      if (!shouldRouteFormToNative(this, null)) {
        return originalFormSubmit.call(this);
      }

      submitFormThroughNative(this, null).catch(function () {
        originalFormSubmit.call(this);
      }.bind(this));
    };
  }

  function NativeXMLHttpRequest() {
    this._delegate = null;
    this._headers = {};
    this._listeners = {};
    this._method = "GET";
    this._responseHeaders = {};
    this._url = "";
    this.readyState = NativeXMLHttpRequest.UNSENT;
    this.response = "";
    this.responseText = "";
    this.responseType = "";
    this.responseURL = "";
    this.status = 0;
    this.statusText = "";
    this.onreadystatechange = null;
    this.onload = null;
    this.onerror = null;
    this.onloadend = null;
  }

  NativeXMLHttpRequest.UNSENT = 0;
  NativeXMLHttpRequest.OPENED = 1;
  NativeXMLHttpRequest.HEADERS_RECEIVED = 2;
  NativeXMLHttpRequest.LOADING = 3;
  NativeXMLHttpRequest.DONE = 4;

  NativeXMLHttpRequest.prototype.addEventListener = function (type, listener) {
    if (!this._listeners[type]) {
      this._listeners[type] = [];
    }

    this._listeners[type].push(listener);
  };

  NativeXMLHttpRequest.prototype.removeEventListener = function (type, listener) {
    if (!this._listeners[type]) {
      return;
    }

    this._listeners[type] = this._listeners[type].filter(function (candidate) {
      return candidate !== listener;
    });
  };

  NativeXMLHttpRequest.prototype._emit = function (type) {
    if (typeof this["on" + type] === "function") {
      this["on" + type].call(this);
    }

    (this._listeners[type] || []).forEach(function (listener) {
      listener.call(this);
    }, this);
  };

  NativeXMLHttpRequest.prototype._syncFromDelegate = function () {
    if (!this._delegate) {
      return;
    }

    this.readyState = this._delegate.readyState;
    this.response = this._delegate.response;
    this.responseText = this._delegate.responseText;
    this.responseURL = this._delegate.responseURL;
    this.status = this._delegate.status;
    this.statusText = this._delegate.statusText;
  };

  NativeXMLHttpRequest.prototype.open = function (method, url, async, user, password) {
    const resolvedUrl = new URL(url, window.location.href).toString();
    this._method = (method || "GET").toUpperCase();
    this._url = resolvedUrl;

    if (async === false) {
      throw new Error("Synchronous XMLHttpRequest is not supported by the Approov bridge.");
    }

    if (!shouldRouteToNative(resolvedUrl)) {
      this._delegate = new OriginalXMLHttpRequest();
      this._delegate.responseType = this.responseType;

      ["readystatechange", "load", "error", "loadend", "abort", "timeout"].forEach(function (eventName) {
        this._delegate.addEventListener(eventName, function () {
          this._syncFromDelegate();
          this._emit(eventName);
        }.bind(this));
      }, this);

      this._delegate.open(method, url, async, user, password);
      return;
    }

    this._delegate = null;
    this._headers = {};
    this.readyState = NativeXMLHttpRequest.OPENED;
    this._emit("readystatechange");
  };

  NativeXMLHttpRequest.prototype.setRequestHeader = function (name, value) {
    if (this._delegate) {
      this._delegate.setRequestHeader(name, value);
      return;
    }

    this._headers[name] = value;
  };

  NativeXMLHttpRequest.prototype.send = function (body) {
    if (this._delegate) {
      this._delegate.send(body);
      return;
    }

    performNativeRequest(this._url, {
      body: body,
      headers: this._headers,
      method: this._method
    }).then(function (response) {
      return response.text().then(function (responseText) {
        this.readyState = NativeXMLHttpRequest.DONE;
        this.status = response.status;
        this.statusText = response.statusText;
        this.response = responseText;
        this.responseText = responseText;
        this.responseURL = response.url || this._url;
        this._responseHeaders = {};

        if (typeof response.headers.forEach === "function") {
          response.headers.forEach(function (value, key) {
            this._responseHeaders[key] = value;
          }, this);
        } else {
          this._responseHeaders = response.headers || {};
        }

        this._emit("readystatechange");
        this._emit("load");
        this._emit("loadend");
      }.bind(this));
    }.bind(this)).catch(function (error) {
      this.readyState = NativeXMLHttpRequest.DONE;
      this.status = 0;
      this.statusText = error.message;
      this.response = "";
      this.responseText = "";
      this._emit("readystatechange");
      this._emit("error");
      this._emit("loadend");
    }.bind(this));
  };

  NativeXMLHttpRequest.prototype.abort = function () {
    if (this._delegate) {
      this._delegate.abort();
      return;
    }

    this.status = 0;
    this.statusText = "aborted";
    this.readyState = NativeXMLHttpRequest.DONE;
    this._emit("readystatechange");
    this._emit("abort");
    this._emit("loadend");
  };

  NativeXMLHttpRequest.prototype.getAllResponseHeaders = function () {
    if (this._delegate) {
      return this._delegate.getAllResponseHeaders();
    }

    return Object.keys(this._responseHeaders).map(function (name) {
      return name + ": " + this._responseHeaders[name];
    }, this).join("\r\n");
  };

  NativeXMLHttpRequest.prototype.getResponseHeader = function (name) {
    if (this._delegate) {
      return this._delegate.getResponseHeader(name);
    }

    const headerName = findHeaderValue(this._responseHeaders, name);
    return headerName ? this._responseHeaders[headerName] : null;
  };

  window.XMLHttpRequest = NativeXMLHttpRequest;
})();
