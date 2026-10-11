// Debug/CI-only render-load probe (never shipped in release builds): COUNTS only, used by the
// P0/P1 probe phases to compare what the live page asks of the emulator's renderer with a fixture page.
// Installed at document start AFTER the read-only guard (it wraps whatever WebSocket the guard left).
// Records canvases, canvas context creations by kind, decoded image sizes (pixels), WebSocket byte
// totals, requestAnimationFrame callbacks and workers. Never stores page text, titles, ids or frames.
(function () {
  'use strict';
  if (window.__qaRenderSnap) return;
  var r = {
    ctx: {}, ctxNull: 0, ctxMaxPixels: 0,
    imgLoads: 0, imgPixels: 0, imgMaxPixels: 0,
    bitmaps: 0, bitmapPixels: 0,
    wsOpened: 0, wsMessages: 0, wsTextBytes: 0, wsBinaryBytes: 0,
    raf: 0, workers: 0,
  };
  function bump(kind) { r.ctx[kind] = (r.ctx[kind] || 0) + 1; }

  [window.HTMLCanvasElement, window.OffscreenCanvas].forEach(function (C) {
    if (!C || !C.prototype || !C.prototype.getContext) return;
    var native = C.prototype.getContext;
    C.prototype.getContext = function (kind) {
      var c = native.apply(this, arguments);
      if (c) {
        bump(String(kind));
        r.ctxMaxPixels = Math.max(r.ctxMaxPixels, (this.width || 0) * (this.height || 0));
      } else {
        r.ctxNull++;
      }
      return c;
    };
  });

  window.addEventListener('load', function (e) {
    var t = e.target;
    if (t && t.tagName === 'IMG') {
      var px = (t.naturalWidth || 0) * (t.naturalHeight || 0);
      r.imgLoads++;
      r.imgPixels += px;
      r.imgMaxPixels = Math.max(r.imgMaxPixels, px);
    }
  }, true);

  if (window.createImageBitmap) {
    var nativeBitmap = window.createImageBitmap;
    window.createImageBitmap = function () {
      return nativeBitmap.apply(this, arguments).then(function (b) {
        r.bitmaps++;
        r.bitmapPixels += (b.width || 0) * (b.height || 0);
        return b;
      });
    };
  }

  var nativeRaf = window.requestAnimationFrame;
  if (nativeRaf) {
    window.requestAnimationFrame = function (cb) {
      r.raf++;
      return nativeRaf.call(window, cb);
    };
  }

  if (window.Worker) {
    var NativeWorker = window.Worker;
    window.Worker = function () {
      r.workers++;
      return new (Function.prototype.bind.apply(NativeWorker, [null].concat([].slice.call(arguments))))();
    };
    window.Worker.prototype = NativeWorker.prototype;
  }

  var NativeWs = window.WebSocket;
  if (NativeWs) {
    var Wrapped = function (url, protocols) {
      var ws = protocols === undefined ? new NativeWs(url) : new NativeWs(url, protocols);
      ws.addEventListener('open', function () { r.wsOpened++; });
      ws.addEventListener('message', function (e) {
        var d = e.data;
        r.wsMessages++;
        if (typeof d === 'string') r.wsTextBytes += d.length;
        else if (d && d.byteLength !== undefined) r.wsBinaryBytes += d.byteLength;
        else if (d && d.size !== undefined) r.wsBinaryBytes += d.size;
      });
      return ws;
    };
    Wrapped.prototype = NativeWs.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { Wrapped[k] = NativeWs[k]; });
    window.WebSocket = Wrapped;
  }

  window.__qaRenderSnap = function () {
    var res = {};
    try {
      performance.getEntriesByType('resource').forEach(function (e) {
        var k = e.initiatorType || 'other';
        var s = res[k] || (res[k] = { n: 0, decodedBytes: 0 });
        s.n++;
        s.decodedBytes += e.decodedBodySize || 0;
      });
    } catch (e) { /* counts are best effort */ }
    var snap = JSON.parse(JSON.stringify(r));
    snap.canvasesInDom = document.querySelectorAll('canvas').length;
    snap.imgsInDom = document.querySelectorAll('img').length;
    snap.svgInDom = document.querySelectorAll('svg').length;
    snap.domNodes = document.getElementsByTagName('*').length;
    snap.resources = res;
    return snap;
  };
})();
