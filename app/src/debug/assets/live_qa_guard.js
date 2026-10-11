// Debug-only live-QA guard (never shipped in release builds). Injected at document start.
// Makes the live herdr-web-ui strictly read-only: WebSocket client frames are filtered, the
// WebSocket role is forced to "observe", and any non-GET/HEAD request is rejected.
(function () {
  'use strict';
  if (window.__qaGuardInstalled) return;
  window.__qaGuardInstalled = true;
  window.__qaDropped = 0;
  window.__qaBlocked = 0;
  window.__qaRoleRewritten = 0;
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
      window.__qaRoleRewritten += 1;
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

  // Incoming frames: count server frame types only (never payloads, ids or labels), so a test can tell that
  // the socket really delivered a snapshot / role-ack and not merely that the client opened it.
  window.__qaIncoming = 0;
  window.__qaIncomingTypes = {};
  var TYPE_SHAPE = /^[a-z][a-z0-9-]{0,31}$/;
  var TYPE_HEAD = /^\s*\{\s*"type"\s*:\s*"([^"\\]{1,32})"/;

  function incomingType(data) {
    if (typeof data !== 'string') return 'binary';
    var m = TYPE_HEAD.exec(data.length > 256 ? data.slice(0, 256) : data);
    var type = m ? m[1] : undefined;
    if (type === undefined && data.length <= 65536) {
      try {
        var frame = JSON.parse(data);
        if (frame && typeof frame === 'object' && typeof frame.type === 'string') type = frame.type;
      } catch (e) { /* counted as other */ }
    }
    return type !== undefined && TYPE_SHAPE.test(type) ? type : 'other';
  }

  function countIncoming(event) {
    var key = incomingType(event && event.data);
    window.__qaIncoming += 1;
    window.__qaIncomingTypes[key] = (window.__qaIncomingTypes[key] || 0) + 1;
  }

  var NativeWebSocket = window.WebSocket;
  if (NativeWebSocket) {
    var CountingWebSocket = function (url, protocols) {
      var ws = protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
      ws.addEventListener('message', countIncoming);
      return ws;
    };
    CountingWebSocket.prototype = NativeWebSocket.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { CountingWebSocket[k] = NativeWebSocket[k]; });
    window.WebSocket = CountingWebSocket;
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
