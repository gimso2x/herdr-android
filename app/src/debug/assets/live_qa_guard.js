// Debug-only live-QA guard (never shipped in release builds). Injected at document start.
// Makes the live herdr-web-ui strictly read-only: WebSocket client frames are filtered, the
// WebSocket role is forced to "observe", and any non-GET/HEAD request is rejected.
(function () {
  'use strict';
  if (window.__qaGuardInstalled) return;
  window.__qaGuardInstalled = true;
  window.__qaDropped = 0;
  window.__qaBlocked = 0;
  window.__qaDroppedTypes = {};

  // Client frame types from herdr-web-ui src/lib/ws.ts. Keep these two lists in sync with the
  // plan; LiveQaGuardScriptTest compares them literally.
  var DROP_TYPES = ['input', 'keys', 'pty-data', 'resize', 'submit', 'pending-action', 'secret', 'take-over'];
  var ALLOW_TYPES = ['attach', 'detach', 'pty-ack'];

  function drop(type) {
    window.__qaDropped += 1;
    var key = String(type);
    window.__qaDroppedTypes[key] = (window.__qaDroppedTypes[key] || 0) + 1;
  }

  // Returns the payload to send, or undefined to drop. An unknown type is dropped.
  function filterFrame(data) {
    if (typeof data !== 'string') { drop('non-text'); return undefined; }
    var frame;
    try { frame = JSON.parse(data); } catch (e) { drop('unparsable'); return undefined; }
    var type = frame && typeof frame === 'object' ? frame.type : undefined;
    if (type === 'role') {
      frame.mode = 'observe';
      return JSON.stringify(frame);
    }
    if (ALLOW_TYPES.indexOf(type) !== -1) return data;
    // DROP_TYPES and every type not listed anywhere end up here.
    drop(type);
    return undefined;
  }

  var originalSend = window.WebSocket && window.WebSocket.prototype.send;
  if (originalSend) {
    window.WebSocket.prototype.send = function (data) {
      var out = filterFrame(data);
      if (out === undefined) return undefined;
      return originalSend.call(this, out);
    };
  }

  function methodAllowed(method) {
    var m = String(method || 'GET').toUpperCase();
    return m === 'GET' || m === 'HEAD';
  }

  function block() {
    window.__qaBlocked += 1;
  }

  if (typeof window.fetch === 'function') {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var method = (init && init.method) || (input && typeof input === 'object' && input.method) || 'GET';
      if (!methodAllowed(method)) {
        block();
        return Promise.reject(new TypeError('qa-guard: ' + String(method).toUpperCase() + ' blocked'));
      }
      return originalFetch.apply(this, arguments);
    };
  }

  if (window.XMLHttpRequest) {
    var originalOpen = window.XMLHttpRequest.prototype.open;
    window.XMLHttpRequest.prototype.open = function (method) {
      if (!methodAllowed(method)) {
        block();
        throw new Error('qa-guard: ' + String(method).toUpperCase() + ' blocked');
      }
      return originalOpen.apply(this, arguments);
    };
  }

  // sendBeacon is always a POST.
  if (navigator.sendBeacon) {
    navigator.sendBeacon = function () {
      block();
      return false;
    };
  }
})();
