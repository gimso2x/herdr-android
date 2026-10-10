// Debug/CI-only diagnostic probe (never shipped in release builds). Injected at document start
// into the live herdr-web-ui by the terminal diagnostics (Android WebView and headless Chrome).
//
// NARROWED GUARD (differs from live_qa_guard.js, deliberately): the WebSocket role is NOT forced to
// "observe", so the page behaves like the owner's phone session (interact role, attach and resize
// are sent). Everything that could type into or change the owner's workspace is still dropped:
//   dropped WebSocket client frames: input, keys, submit, pending-action, secret, take-over, and any
//   type not listed below; rejected HTTP: every non-GET/HEAD fetch/XHR and sendBeacon.
//   allowed WebSocket client frames: role, attach, detach, pty-ack, resize, watch, unwatch.
//
// The probe records COUNTS and shapes only: frame counts by type, byte totals, rectangles, short
// error messages. It never stores frame contents, pane text, titles or ids.
(function () {
  'use strict';
  if (window.__diag) return;
  var diag = window.__diag = {
    sockets: [], consoleErrors: [], pageErrors: [], resourceErrors: [], cspViolations: [],
    fetches: {}, dropped: {}, blocked: 0,
  };
  var ALLOW = { role: 1, attach: 1, detach: 1, 'pty-ack': 1, resize: 1, watch: 1, unwatch: 1 };

  function bump(map, key) { map[key] = (map[key] || 0) + 1; }
  function clean(value) {
    return String(value).replace(/[^\x20-\x7e]/g, ' ').slice(0, 200);
  }
  function pathOf(url) {
    try { return new URL(url, location.href).pathname; } catch (e) { return 'bad-url'; }
  }
  function push(list, item) { if (list.length < 30) list.push(item); }
  function where() {
    var stack = String(new Error().stack || '').split('\n');
    for (var i = 1; i < stack.length; i++) {
      var m = /(\/assets\/[^\s):]*):(\d+):(\d+)/.exec(stack[i]);
      if (m) return m[1].slice(-60) + ':' + m[2] + ':' + m[3];
    }
    return '';
  }

  // --- console / page errors ---------------------------------------------------------------
  ['error', 'warn'].forEach(function (level) {
    var original = console[level];
    console[level] = function () {
      try {
        var text = Array.prototype.map.call(arguments, function (a) {
          return a instanceof Error ? a.name + ': ' + a.message : typeof a === 'string' ? a : typeof a;
        }).join(' ');
        if (level === 'error') push(diag.consoleErrors, { m: clean(text), src: where() });
        else diag.warnCount = (diag.warnCount || 0) + 1;
      } catch (e) { /* never break the page */ }
      return original.apply(this, arguments);
    };
  });
  window.addEventListener('error', function (event) {
    if (event.target && event.target !== window) {
      var t = event.target;
      push(diag.resourceErrors, { tag: String(t.tagName || '').toLowerCase(), path: pathOf(t.src || t.href || '') });
      return;
    }
    push(diag.pageErrors, {
      m: clean(event.message), src: pathOf(event.filename || '').slice(-60) + ':' + event.lineno + ':' + event.colno,
    });
  }, true);
  window.addEventListener('unhandledrejection', function (event) {
    var reason = event.reason;
    push(diag.pageErrors, {
      m: 'unhandledrejection: ' + clean(reason instanceof Error ? reason.name + ': ' + reason.message : typeof reason),
      src: '',
    });
  });
  document.addEventListener('securitypolicyviolation', function (event) {
    push(diag.cspViolations, {
      directive: event.violatedDirective,
      blocked: event.blockedURI ? String(event.blockedURI).split(':')[0] + ':' + pathOf(event.blockedURI).slice(-40) : '',
    });
  });

  // --- WebSocket: count frames, apply the narrowed guard -------------------------------------
  var Native = window.WebSocket;
  if (Native) {
    var Wrapped = function (url, protocols) {
      var ws = protocols === undefined ? new Native(url) : new Native(url, protocols);
      var rec = {
        path: pathOf(String(url)), opened: false, closeCode: null, errors: 0, recv: {}, sent: {},
        recvTextBytes: 0, recvBinaryBytes: 0, ptyDataMessages: 0, ptyDataChars: 0, firstMessageMs: null,
      };
      var t0 = Date.now();
      diag.sockets.push(rec);
      ws.addEventListener('open', function () { rec.opened = true; });
      ws.addEventListener('error', function () { rec.errors++; });
      ws.addEventListener('close', function (event) { rec.closeCode = event.code; });
      ws.addEventListener('message', function (event) {
        if (rec.firstMessageMs === null) rec.firstMessageMs = Date.now() - t0;
        var data = event.data;
        if (typeof data === 'string') {
          rec.recvTextBytes += data.length;
          var type = 'unparsable';
          try {
            var parsed = JSON.parse(data);
            type = parsed && typeof parsed === 'object' && typeof parsed.type === 'string' ? parsed.type : 'untyped';
            if (type === 'pty-data') {
              rec.ptyDataMessages++;
              if (typeof parsed.data === 'string') rec.ptyDataChars += parsed.data.length;
            }
          } catch (e) { /* counted as unparsable */ }
          bump(rec.recv, type);
        } else if (data instanceof ArrayBuffer) {
          rec.recvBinaryBytes += data.byteLength;
          bump(rec.recv, 'binary-arraybuffer');
        } else if (typeof Blob !== 'undefined' && data instanceof Blob) {
          rec.recvBinaryBytes += data.size;
          bump(rec.recv, 'binary-blob');
        } else {
          bump(rec.recv, 'other');
        }
      });
      var nativeSend = ws.send;
      ws.send = function (data) {
        var type = 'non-text';
        if (typeof data === 'string') {
          try { var f = JSON.parse(data); type = f && typeof f === 'object' && typeof f.type === 'string' ? f.type : 'untyped'; }
          catch (e) { type = 'unparsable'; }
        }
        if (!ALLOW[type]) { bump(diag.dropped, type); return undefined; }
        bump(rec.sent, type);
        return nativeSend.call(ws, data);
      };
      return ws;
    };
    Wrapped.prototype = Native.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { Wrapped[k] = Native[k]; });
    window.WebSocket = Wrapped;
  }

  // --- HTTP: GET/HEAD only, status counts by path (no query) -----------------------------------
  function methodAllowed(method) {
    var m = String(method || 'GET').toUpperCase();
    return m === 'GET' || m === 'HEAD';
  }
  if (typeof window.fetch === 'function') {
    var nativeFetch = window.fetch;
    window.fetch = function (input, init) {
      var method = (init && init.method) || (input && typeof input === 'object' && input.method) || 'GET';
      if (!methodAllowed(method)) {
        diag.blocked++;
        return Promise.reject(new TypeError('diag-guard: ' + String(method).toUpperCase() + ' blocked'));
      }
      var path = pathOf(typeof input === 'string' ? input : input && input.url ? input.url : String(input));
      return nativeFetch.apply(this, arguments).then(function (response) {
        bump(diag.fetches, path + ' ' + response.status);
        return response;
      }, function (error) {
        bump(diag.fetches, path + ' error');
        throw error;
      });
    };
  }
  if (window.XMLHttpRequest) {
    var nativeOpen = window.XMLHttpRequest.prototype.open;
    window.XMLHttpRequest.prototype.open = function (method) {
      if (!methodAllowed(method)) { diag.blocked++; throw new Error('diag-guard: ' + String(method).toUpperCase() + ' blocked'); }
      return nativeOpen.apply(this, arguments);
    };
  }
  if (navigator.sendBeacon) navigator.sendBeacon = function () { diag.blocked++; return false; };
})();
