/*
 * SunnyIPTV source recognition: turns pasted text, a QR code or OCR text from a provider message into
 * Xtream or M3U fields. Runs in the browser (nothing leaves the device) and in Node for the tests.
 * The Android app has the same rules in core/SourceParse.java, checked against tools/tests/source-parse-cases.json.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.SunnySourceParse = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var LABELS = {
    username: ['user name', 'username', 'gebruikersnaam', 'benutzername', 'gebruiker', 'benutzer', 'login', 'user'],
    password: ['password', 'passwort', 'wachtwoord', 'pass', 'pwd', 'pw'],
    server: ['server url', 'xtream url', 'serveradres', 'server', 'portal', 'host', 'dns', 'url'],
    port: ['port', 'poort'],
    mac: ['mac address', 'mac-adres', 'mac']
  };
  var MAC = /\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\b/i;

  function clean(text) {
    return String(text || '')
      .replace(/[​-‍⁠﻿]/g, '')
      .replace(/[   ]/g, ' ')
      .replace(/[‘’‚‛]/g, "'")
      .replace(/[“”„]/g, '"')
      .replace(/\r\n?/g, '\n');
  }

  /** OCR splits URLs with spaces ("http: //host. tv"); a URL never contains spaces, so they are removed after "http". */
  function joinOcrUrls(text) {
    return text.split('\n').map(function (line) {
      var i = line.search(/https?\s*:/i);
      return i < 0 ? line : line.slice(0, i) + line.slice(i).replace(/\s+/g, '');
    }).join('\n');
  }

  function trimUrl(u) { return u.replace(/[.,;:!?)\]}*'"]+$/, ''); }

  function parseUrl(u) {
    try { var x = new URL(u); return /^https?:$/.test(x.protocol) && x.hostname ? x : null; } catch (e) { return null; }
  }

  function param(url, names) {
    var found = '';
    url.searchParams.forEach(function (value, key) {
      if (!found && names.indexOf(key.toLowerCase()) >= 0) found = value;
    });
    return found.trim();
  }

  function fromUrl(u) {
    var x = parseUrl(u);
    if (!x) return null;
    var user = param(x, ['username', 'user']), pass = param(x, ['password', 'pass']);
    if (user && pass && /(get|player_api|xmltv|panel_api)\.php$/i.test(x.pathname))
      return { type: 'XTREAM', server: x.origin, username: user, password: pass };
    var path = x.pathname.match(/^\/(?:live|movie|series|timeshift)\/([^/]+)\/([^/]+)\/[^/]+$/i);
    if (path) return { type: 'XTREAM', server: x.origin, username: decodeURIComponent(path[1]), password: decodeURIComponent(path[2]) };
    if (/\.m3u8?$/i.test(x.pathname) || /m3u/i.test(x.search) || /m3u/i.test(x.pathname))
      return { type: 'M3U', m3u: x.href };
    return null;
  }

  function labelled(text) {
    var out = {};
    text.split('\n').forEach(function (line) {
      Object.keys(LABELS).forEach(function (field) {
        if (out[field]) return;
        LABELS[field].some(function (label) {
          var re = new RegExp('^[^A-Za-z0-9]*' + label.replace(/[-]/g, '\\-') + '[^A-Za-z0-9:=]*[:=]\\s*(.+)$', 'i');
          var m = line.trim().match(re);
          if (!m) return false;
          var value = m[1].trim().replace(/^[*_"'`]+|[*_"'`]+$/g, '').trim();
          if (value) out[field] = value;
          return !!value;
        });
      });
    });
    return out;
  }

  function serverFrom(value, port) {
    if (!value) return '';
    var v = trimUrl(value.trim());
    if (!/^https?:\/\//i.test(v)) v = 'http://' + v.replace(/^\/+/, '');
    var x = parseUrl(v);
    if (!x) return '';
    if (port && /^\d{2,5}$/.test(port) && !x.port) x.port = port;
    return x.origin;
  }

  /**
   * @param {string} text pasted text, a QR payload or OCR output
   * @param {boolean} ocr true for text from a photo: URLs split by spaces are joined
   * @return {{type:string, server?:string, username?:string, password?:string, m3u?:string, reason?:string}}
   *   type XTREAM, M3U, UNSUPPORTED (reason mac_portal) or NONE. XTREAM may have an empty server: the viewer adds it.
   */
  function parse(text, ocr) {
    var t = clean(text);
    if (ocr) t = joinOcrUrls(t);
    var urls = (t.match(/https?:\/\/[^\s<>"'`]+/gi) || []).map(trimUrl);
    var m3u = null;
    for (var i = 0; i < urls.length; i++) {
      var r = fromUrl(urls[i]);
      if (r && r.type === 'XTREAM') return r;
      if (r && r.type === 'M3U' && !m3u) m3u = r;
    }
    var f = labelled(t);
    if (f.username && f.password) {
      var server = serverFrom(f.server, f.port);
      if (!server) {
        var other = urls.map(parseUrl).filter(function (x) { return x && !(m3u && x.href === m3u.m3u); })[0];
        if (other) server = serverFrom(other.origin, f.port);
      }
      return { type: 'XTREAM', server: server, username: f.username, password: f.password };
    }
    if (m3u) return m3u;
    if ((f.mac && MAC.test(f.mac)) || (MAC.test(t) && /portal|\/c\/?/i.test(t))) return { type: 'UNSUPPORTED', reason: 'mac_portal' };
    return { type: 'NONE' };
  }

  return { parse: parse };
});
