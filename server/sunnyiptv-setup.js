/* SunnyIPTV provider setup page: paste, scan (QR or photo, on this device), recognise, check the connection, save. */
(function () {
  'use strict';
  var form = document.querySelector('.nv-smart-form');
  if (!form || !window.SunnySourceParse) return;
  var T = JSON.parse(form.dataset.text || '{}');
  var DEMO_URL = 'https://sunnyiptv.com/sunnyiptv-demo.m3u';
  var text = form.querySelector('#nv-smart-text');
  var status = form.querySelector('.nv-smart-status');
  var check = form.querySelector('.nv-check');
  var save = form.querySelector('.nv-save');
  var typeInput = form.querySelector('input[name="source_type"]');
  var field = function (name) { return form.querySelector('[name="' + name + '"]'); };
  var verified = false;

  function say(el, msg, kind) {
    el.textContent = msg || '';
    el.className = el.className.replace(/\s*is-(ok|warn|err|busy)/g, '') + (kind ? ' is-' + kind : '');
    el.hidden = !msg;
  }
  function fmt(s) { var a = [].slice.call(arguments, 1); return s.replace(/%(\d)\$s/g, function (_, i) { return a[i - 1]; }); }

  function kind() { var c = form.querySelector('input[name="source_kind"]:checked'); return c ? c.value : 'XTREAM'; }
  function setKind(value) {
    var radio = form.querySelector('input[name="source_kind"][value="' + value + '"]');
    if (radio) radio.checked = true;
    update();
  }
  function update() {
    var k = kind();
    form.querySelectorAll('[data-source-kind]').forEach(function (box) {
      var show = box.dataset.sourceKind === k;
      box.hidden = !show;
      box.querySelectorAll('input').forEach(function (i) { i.disabled = !show; });
    });
    form.querySelectorAll('.nv-kind').forEach(function (l) { l.classList.toggle('is-active', l.querySelector('input').checked); });
    typeInput.value = k === 'XTREAM' ? 'XTREAM' : 'M3U';
    verified = false; say(check, '');
  }
  form.querySelectorAll('input[name="source_kind"]').forEach(function (r) { r.addEventListener('change', update); });
  ['source_server', 'source_username', 'source_password', 'source_m3u'].forEach(function (n) {
    var f = field(n); if (f) f.addEventListener('input', function () { verified = false; say(check, ''); });
  });

  function flash(el) { el.classList.remove('nv-filled'); void el.offsetWidth; el.classList.add('nv-filled'); }
  function apply(r, fromPhoto) {
    var note = fromPhoto ? ' ' + T.ocr_note : '';
    if (r.type === 'XTREAM') {
      setKind('XTREAM');
      [['source_server', r.server], ['source_username', r.username], ['source_password', r.password]].forEach(function (p) {
        if (p[1]) { field(p[0]).value = p[1]; flash(field(p[0])); }
      });
      say(status, (r.server ? T.found_x : T.found_partial) + note, fromPhoto ? 'warn' : 'ok');
      if (!r.server) field('source_server').focus();
    } else if (r.type === 'M3U') {
      setKind('M3U'); field('source_m3u').value = r.m3u; flash(field('source_m3u'));
      say(status, T.found_m + note, fromPhoto ? 'warn' : 'ok');
    } else if (r.type === 'UNSUPPORTED') say(status, T.mac, 'err');
    else say(status, T.none, 'warn');
  }

  var timer;
  text.addEventListener('input', function () {
    clearTimeout(timer);
    timer = setTimeout(function () { if (text.value.trim()) apply(SunnySourceParse.parse(text.value, false), false); else say(status, ''); }, 250);
  });

  form.querySelector('[data-paste]').addEventListener('click', function () {
    if (navigator.clipboard && navigator.clipboard.readText) {
      navigator.clipboard.readText().then(function (v) {
        if (!v) { text.focus(); say(status, T.paste_hint, 'warn'); return; }
        text.value = v; apply(SunnySourceParse.parse(v, false), false);
      }, function () { text.focus(); say(status, T.paste_hint, 'warn'); });
    } else { text.focus(); say(status, T.paste_hint, 'warn'); }
  });

  function load(src) {
    return new Promise(function (ok, fail) {
      var s = document.createElement('script'); s.src = src; s.async = true; s.onload = ok; s.onerror = fail; document.head.appendChild(s);
    });
  }
  function imageFrom(file) {
    return new Promise(function (ok, fail) {
      var img = new Image(); img.onload = function () { ok(img); }; img.onerror = fail; img.src = URL.createObjectURL(file);
    });
  }
  function canvasOf(img, max) {
    var scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
    var c = document.createElement('canvas'); c.width = Math.round(img.naturalWidth * scale); c.height = Math.round(img.naturalHeight * scale);
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height); return c;
  }
  function readQr(img) {
    if ('BarcodeDetector' in window) {
      return new BarcodeDetector({ formats: ['qr_code'] }).detect(img).then(function (codes) { return codes.length ? codes[0].rawValue : ''; }, function () { return ''; });
    }
    return load('https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.js').then(function () {
      var c = canvasOf(img, 1600), d = c.getContext('2d').getImageData(0, 0, c.width, c.height);
      var q = window.jsQR(d.data, c.width, c.height); return q ? q.data : '';
    }, function () { return ''; });
  }
  function readText(img) {
    var go = window.Tesseract ? Promise.resolve() : load('https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js');
    return go.then(function () { return window.Tesseract.recognize(canvasOf(img, 2000), 'eng'); }).then(function (r) { return r.data.text || ''; });
  }
  form.querySelector('[data-scan]').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0]; e.target.value = '';
    if (!file) return;
    say(status, T.reading, 'busy');
    imageFrom(file).then(function (img) {
      return readQr(img).then(function (qr) {
        if (qr) { text.value = qr; apply(SunnySourceParse.parse(qr, false), false); return; }
        say(status, T.ocr, 'busy');
        return readText(img).then(function (ocr) {
          text.value = ocr.trim(); apply(SunnySourceParse.parse(ocr, true), true);
        });
      }).finally(function () { URL.revokeObjectURL(img.src); });
    }).catch(function () { say(status, T.scan_fail, 'err'); });
  });

  form.querySelector('[data-eye]').addEventListener('click', function (e) {
    var p = field('source_password'), show = p.type === 'password';
    p.type = show ? 'text' : 'password'; e.target.textContent = show ? e.target.dataset.hide : e.target.dataset.show;
  });

  function submitNow() {
    if (kind() === 'DEMO') { var m = field('source_m3u'); m.disabled = false; m.value = DEMO_URL; typeInput.value = 'M3U'; }
    save.disabled = true; save.textContent = T.saving; form.submit();
  }
  function anyway() {
    var b = document.createElement('button'); b.type = 'button'; b.className = 'nv-ghost'; b.textContent = T.anyway;
    b.addEventListener('click', submitNow); check.appendChild(document.createTextNode(' ')); check.appendChild(b);
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    // Clean values the way the server expects them: no spaces around addresses and names.
    ['source_server', 'source_username', 'source_m3u'].forEach(function (n) { var f = field(n); if (f && !f.disabled) f.value = f.value.trim(); });
    var srv = field('source_server');
    if (!srv.disabled && srv.value && !/^https?:\/\//i.test(srv.value)) srv.value = 'http://' + srv.value;
    if (!form.checkValidity()) { form.reportValidity(); return; }
    if (kind() === 'DEMO' || verified) { submitNow(); return; }
    var k = kind();
    var body = k === 'XTREAM'
      ? { type: 'XTREAM', server: srv.value, username: field('source_username').value, password: field('source_password').value }
      : { type: 'M3U', m3u: field('source_m3u').value };
    say(check, T.checking, 'busy'); save.disabled = true;
    fetch(form.dataset.check, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-WP-Nonce': form.dataset.nonce }, body: JSON.stringify(body) })
      .then(function (r) { return r.json(); })
      .then(function (r) {
        save.disabled = false;
        if (r.ok) {
          verified = true;
          var msg = r.kind === 'm3u' ? T.ok_m
            : r.expires ? fmt(T.ok_x, r.status, new Date(r.expires * 1000).toLocaleDateString(form.dataset.lang), r.max_connections || '?')
            : fmt(T.ok_x_short, r.status);
          say(check, '✓ ' + msg, 'ok');
          setTimeout(submitNow, 900);
          return;
        }
        var msgs = { auth: T.auth, expired: fmt(T.expired || '', r.status || ''), unreachable: T.unreachable, not_xtream: T.not_xtream, not_m3u: T.not_m3u, missing: T.missing, busy: T.busy };
        say(check, msgs[r.code] || T.unreachable, 'err');
        if (r.code !== 'missing' && r.code !== 'busy') anyway();
      })
      .catch(function () { save.disabled = false; say(check, T.unreachable, 'err'); anyway(); });
  });

  update();
})();
