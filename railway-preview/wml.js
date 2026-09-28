/*
 * Minimal WML 1.x browser for the archived game.
 *
 * The server embeds the original WML deck as JSON in #wml-src; this script
 * parses it and renders one card at a time the way a WAP phone would:
 * cards and #fragment navigation, browser variables ($(var), <setvar>, input,
 * select), tasks (<go>, <prev/>, <refresh/>, <noop/>), soft-key menus (<do>),
 * timers and onenterforward events. Pages are built from DOM nodes only, never
 * from HTML strings, and navigation is limited to this site.
 */
(function () {
  'use strict';

  var VARS_KEY = 'wml.vars';
  var root = document.getElementById('wml-root');
  var bar = document.getElementById('wml-bar');
  var titleEl = document.getElementById('wml-title');

  // ---------------------------------------------------------------- variables
  var vars = {};
  try { vars = JSON.parse(sessionStorage.getItem(VARS_KEY) || '{}') || {}; } catch (e) { vars = {}; }
  function saveVars() { try { sessionStorage.setItem(VARS_KEY, JSON.stringify(vars)); } catch (e) {} }
  function getVar(name) { return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : ''; }
  function setVar(name, value) { vars[name] = value == null ? '' : String(value); saveVars(); }

  function escapeValue(v) {
    return encodeURIComponent(v).replace(/[!'()*]/g, function (c) {
      return '%' + c.charCodeAt(0).toString(16).toUpperCase();
    });
  }
  function unescapeValue(v) { try { return decodeURIComponent(v.replace(/\+/g, ' ')); } catch (e) { return v; } }

  // WML substitution: $$, $(name), $(name:conv), $name
  function subst(str) {
    if (!str || str.indexOf('$') < 0) return str || '';
    return str.replace(/\$\$|\$\(\s*([A-Za-z_][\w]*)\s*(?::\s*([A-Za-z]+)\s*)?\)|\$([A-Za-z_][\w]*)/g,
      function (m, name, conv, bare) {
        if (m === '$$') return '$';
        var v = getVar(name || bare);
        conv = (conv || '').toLowerCase();
        if (conv === 'e' || conv === 'escape') return escapeValue(v);
        if (conv === 'u' || conv === 'unesc') return unescapeValue(v);
        return v;
      });
  }

  // ------------------------------------------------------------------ parsing
  var NAMED = { nbsp: 160, shy: 173, copy: 169, reg: 174, laquo: 171, raquo: 187, mdash: 8212, ndash: 8211, hellip: 8230, middot: 183, deg: 176, times: 215, euro: 8364 };
  function prepare(text, forHtml) {
    text = text.replace(/^﻿/, '')
      .replace(/<\?xml[^>]*\?>/i, '')
      .replace(/<!DOCTYPE[^>]*>/i, '')
      .replace(/&(?!#\d+;|#x[0-9a-fA-F]+;|(?:amp|lt|gt|quot|apos);)([A-Za-z]+);/g, function (m, n) {
        var code = NAMED[n.toLowerCase()];
        return code ? '&#' + code + ';' : '&amp;' + n + ';';
      })
      .replace(/&(?![A-Za-z]+;|#\d+;|#x[0-9a-fA-F]+;)/g, '&amp;');
    if (forHtml) {
      // The HTML parser does not understand <go .../>: expand self-closing tags.
      text = text.replace(/<([A-Za-z][\w-]*)((?:\s+[^<>]*?)?)\s*\/>/g, function (m, tag, attrs) {
        return /^(br|img|input|meta)$/i.test(tag) ? m : '<' + tag + attrs + '></' + tag + '>';
      });
    }
    return text;
  }
  function parseDeck(text) {
    var doc = new DOMParser().parseFromString(prepare(text, false), 'application/xml');
    var err = doc.getElementsByTagName('parsererror')[0];
    if (!err && doc.documentElement) return doc.documentElement;
    if (window.console) console.warn('[wml] not well-formed, using tolerant parser:', err ? err.textContent : '');
    var html = new DOMParser().parseFromString('<body>' + prepare(text, true) + '</body>', 'text/html');
    return html.body.querySelector('wml') || html.body;
  }

  function tag(el) { return (el.localName || el.nodeName || '').toLowerCase(); }
  function kids(el) { return Array.prototype.slice.call(el.childNodes || []); }
  function attr(el, name) { return el.getAttribute ? el.getAttribute(name) : null; }
  function children(el, name) { return kids(el).filter(function (c) { return c.nodeType === 1 && tag(c) === name; }); }
  function first(el, names) { return kids(el).filter(function (c) { return c.nodeType === 1 && names.indexOf(tag(c)) >= 0; })[0] || null; }

  var deckRoot = parseDeck(JSON.parse(document.getElementById('wml-src').textContent));
  var cards = [];
  (function collect(el) {
    kids(el).forEach(function (c) {
      if (c.nodeType !== 1) return;
      if (tag(c) === 'card') cards.push(c); else collect(c);
    });
  })(deckRoot);
  var templateEl = null;
  (function findTemplate(el) {
    kids(el).forEach(function (c) { if (c.nodeType === 1) { if (tag(c) === 'template') templateEl = c; else if (tag(c) !== 'card') findTemplate(c); } });
  })(deckRoot);
  function cardById(id) {
    for (var i = 0; i < cards.length; i++) if (attr(cards[i], 'id') === id) return cards[i];
    return null;
  }

  // -------------------------------------------------------------------- tasks
  function applySetvars(taskEl) {
    if (!taskEl) return;
    children(taskEl, 'setvar').forEach(function (s) { setVar(attr(s, 'name'), subst(attr(s, 'value') || '')); });
  }

  function sameOrigin(url) { return url.origin === location.origin; }
  // The forum (no database dump) and SMS payments are closed on this server.
  function isClosed(url) { return /^\/(forum|sms)(\/|$)/i.test(url.pathname); }

  var timers = [];
  function clearTimers() { timers.forEach(clearTimeout); timers = []; }

  function run(taskEl) {
    if (!taskEl) return;
    var t = tag(taskEl);
    commitInputs();
    if (t === 'go') {
      applySetvars(taskEl);
      var href = subst(attr(taskEl, 'href') || '');
      var fields = children(taskEl, 'postfield').map(function (f) {
        return [subst(attr(f, 'name') || ''), subst(attr(f, 'value') || '')];
      }).filter(function (f) { return f[0]; });
      navigate(href, (attr(taskEl, 'method') || 'get').toLowerCase(), fields);
    } else if (t === 'prev') {
      applySetvars(taskEl);
      clearTimers();
      history.back();
    } else if (t === 'refresh') {
      applySetvars(taskEl);
      showCard(currentCard, false);
    }
    // noop: nothing
  }

  function navigate(href, method, fields) {
    href = (href || '').trim();
    if (!href) return;
    if (href.charAt(0) === '#') {
      var target = cardById(href.slice(1).split('?')[0]);
      if (target) {
        history.pushState({ card: attr(target, 'id') }, '', '#' + attr(target, 'id'));
        showCard(target, true);
      }
      return;
    }
    var url;
    try { url = new URL(href, location.href); } catch (e) { return; }
    if (!sameOrigin(url) || isClosed(url)) return;
    clearTimers();
    // Login and registration carry the password: keep it out of the URL (and
    // out of proxy logs). bootstrap.php exposes POST fields as $_GET as well.
    if (method !== 'post' && /\/(f_connect|gamereg2?)\.php$/i.test(url.pathname)) {
      url.searchParams.forEach(function (value, key) { fields.unshift([key, value]); });
      url.search = '';
      method = 'post';
    }
    if (method === 'post') {
      var form = document.createElement('form');
      form.method = 'post';
      form.action = url.pathname + url.search;
      fields.forEach(function (f) {
        var i = document.createElement('input');
        i.type = 'hidden'; i.name = f[0]; i.value = f[1];
        form.appendChild(i);
      });
      document.body.appendChild(form);
      form.submit();
    } else {
      fields.forEach(function (f) { url.searchParams.append(f[0], f[1]); });
      location.assign(url.pathname + url.search + url.hash);
    }
  }

  // ---------------------------------------------------------------- rendering
  var currentCard = null;
  var liveInputs = [];

  function commitInputs() {
    liveInputs.forEach(function (i) { setVar(i.name, i.value); });
  }

  function makeAction(label, taskEl, className) {
    var a = document.createElement('a');
    a.href = '#';
    a.className = className || '';
    a.appendChild(label);
    a.addEventListener('click', function (e) { e.preventDefault(); run(taskEl); });
    return a;
  }

  function linkTask(href) {
    // Represent <a href> as a synthetic <go> so it follows the same path.
    var go = document.createElementNS(null, 'go');
    go.setAttribute('href', href);
    return go;
  }

  function isExternal(href) {
    var h = subst(href || '').trim();
    if (!h || h.charAt(0) === '#') return false;
    try { var u = new URL(h, location.href); return !sameOrigin(u) || isClosed(u); } catch (e) { return true; }
  }

  var accessKeys = {};
  var softKeys = [];

  function renderChildren(src, dst) {
    kids(src).forEach(function (n) { renderNode(n, dst); });
  }

  function renderNode(n, dst) {
    if (n.nodeType === 3 || n.nodeType === 4) {
      dst.appendChild(document.createTextNode(subst(n.nodeValue)));
      return;
    }
    if (n.nodeType !== 1) return;
    var t = tag(n), el;
    switch (t) {
      case 'p':
        el = document.createElement('div');
        el.className = 'p';
        var align = attr(n, 'align');
        if (align && /^(left|center|right)$/.test(align)) el.style.textAlign = align;
        renderChildren(n, el);
        dst.appendChild(el);
        return;
      case 'br': dst.appendChild(document.createElement('br')); return;
      case 'b': case 'i': case 'u': case 'small': case 'big': case 'em': case 'strong':
      case 'table': case 'tr': case 'td':
        el = document.createElement(t === 'big' ? 'strong' : t);
        renderChildren(n, el);
        dst.appendChild(el);
        return;
      case 'fieldset':
        el = document.createElement('div');
        renderChildren(n, el);
        dst.appendChild(el);
        return;
      case 'img':
        var src = subst(attr(n, 'src') || '').trim();
        try {
          var u = new URL(src, location.href);
          if (!sameOrigin(u)) throw 0;
          el = document.createElement('img');
          el.src = u.pathname + u.search;
          el.alt = subst(attr(n, 'alt') || '');
          dst.appendChild(el);
        } catch (e) {
          dst.appendChild(document.createTextNode(subst(attr(n, 'alt') || '')));
        }
        return;
      case 'a':
        var href = attr(n, 'href') || '';
        var label = document.createDocumentFragment();
        renderChildren(n, label);
        if (isExternal(href)) {
          el = document.createElement('span');
          el.className = 'unavailable';
          el.title = 'Ссылка недоступна на этом сервере';
          el.appendChild(label);
        } else {
          el = makeAction(label, linkTask(href));
        }
        registerAccessKey(n, el);
        dst.appendChild(el);
        return;
      case 'anchor':
        var task = first(n, ['go', 'prev', 'refresh', 'noop']);
        var text = document.createDocumentFragment();
        kids(n).forEach(function (c) { if (c !== task) renderNode(c, text); });
        if (task && tag(task) === 'go' && isExternal(attr(task, 'href'))) {
          el = document.createElement('span');
          el.className = 'unavailable';
          el.appendChild(text);
        } else {
          el = makeAction(text, task);
        }
        registerAccessKey(n, el);
        dst.appendChild(el);
        return;
      case 'input':
        renderInput(n, dst);
        return;
      case 'select':
        renderSelect(n, dst);
        return;
      case 'do':
        softKeys.push(n);
        return;
      case 'go': case 'prev': case 'refresh': case 'noop': case 'setvar': case 'postfield':
      case 'onevent': case 'timer': case 'head': case 'meta': case 'access': case 'template':
        return;
      default:
        renderChildren(n, dst);
    }
  }

  function registerAccessKey(n, el) {
    var k = attr(n, 'accesskey');
    if (k && /^[0-9*#]$/.test(k)) { accessKeys[k] = el; el.setAttribute('data-key', k); }
  }

  function renderInput(n, dst) {
    var name = attr(n, 'name');
    if (!name || !/^[A-Za-z_][\w]*$/.test(name)) return;
    var input = document.createElement('input');
    var isPass = (attr(n, 'type') || '').toLowerCase() === 'password';
    input.type = isPass ? 'password' : 'text';
    input.name = name;
    var initial = Object.prototype.hasOwnProperty.call(vars, name) ? getVar(name) : subst(attr(n, 'value') || '');
    // The preview login uses a longer password than the game's 10-character
    // limit; bootstrap.php maps it to the game's own key.
    var max = parseInt(attr(n, 'maxlength'), 10);
    if (max > 0 && !(isPass && name === 'pass')) {
      input.maxLength = max;
      if (initial.length > max) initial = initial.slice(0, max);
    }
    input.value = initial;
    setVar(name, initial);
    var fmt = attr(n, 'format') || '';
    if (/^\*?N$|^N+$/.test(fmt)) input.inputMode = 'numeric';
    input.autocomplete = isPass ? 'current-password' : 'off';
    var title = attr(n, 'title');
    if (title) input.placeholder = subst(title);
    input.addEventListener('input', function () { setVar(name, input.value); });
    input.addEventListener('keydown', function (e) { e.stopPropagation(); });
    liveInputs.push(input);
    dst.appendChild(input);
  }

  function renderSelect(n, dst) {
    var name = attr(n, 'name');
    var sel = document.createElement('select');
    if (name) sel.name = name;
    var options = [];
    (function collectOptions(el, parent) {
      kids(el).forEach(function (c) {
        if (c.nodeType !== 1) return;
        if (tag(c) === 'optgroup') {
          var g = document.createElement('optgroup');
          g.label = subst(attr(c, 'title') || '');
          parent.appendChild(g);
          collectOptions(c, g);
        } else if (tag(c) === 'option') {
          var o = document.createElement('option');
          o.value = subst(attr(c, 'value') || '');
          o.textContent = subst(c.textContent || '').trim();
          parent.appendChild(o);
          options.push({ el: o, src: c });
        }
      });
    })(n, sel);
    var current = name && Object.prototype.hasOwnProperty.call(vars, name) ? getVar(name) : subst(attr(n, 'value') || '');
    var picked = options.filter(function (o) { return o.el.value === current; })[0] || options[0];
    if (picked) { picked.el.selected = true; if (name) setVar(name, picked.el.value); }
    sel.addEventListener('change', function () {
      var o = options[sel.selectedIndex];
      if (name) setVar(name, sel.value);
      if (!o) return;
      var pick = attr(o.src, 'onpick');
      if (pick) { run(linkTask(pick)); return; }
      var ev = children(o.src, 'onevent').filter(function (e) { return attr(e, 'type') === 'onpick'; })[0];
      if (ev) run(first(ev, ['go', 'prev', 'refresh', 'noop']));
    });
    sel.addEventListener('keydown', function (e) { e.stopPropagation(); });
    liveInputs.push(sel);
    dst.appendChild(sel);
  }

  function eventTask(card, type) {
    var direct = attr(card, type) || (type === 'ontimer' ? attr(card, 'ontimer') : null);
    if (direct) return linkTask(direct);
    var ev = children(card, 'onevent').filter(function (e) { return attr(e, 'type') === type; })[0];
    if (!ev && templateEl) ev = children(templateEl, 'onevent').filter(function (e) { return attr(e, 'type') === type; })[0];
    return ev ? first(ev, ['go', 'prev', 'refresh', 'noop']) : null;
  }

  function showCard(card, forward) {
    clearTimers();
    currentCard = card;
    liveInputs = [];
    accessKeys = {};
    softKeys = [];
    if (attr(card, 'newcontext') === 'true') { vars = {}; saveVars(); }

    var title = subst(attr(card, 'title') || '');
    titleEl.textContent = title || 'Территория Зла';
    document.title = title || 'Территория Зла';

    var body = document.createDocumentFragment();
    renderChildren(card, body);
    root.textContent = '';
    root.appendChild(body);

    // Soft keys: the card's own <do>, then deck template ones not overridden.
    var own = softKeys.slice();
    if (templateEl) {
      children(templateEl, 'do').forEach(function (d) {
        var dn = attr(d, 'name');
        if (!own.some(function (o) { return dn && attr(o, 'name') === dn; })) own.push(d);
      });
    }
    bar.textContent = '';
    own.forEach(function (d) {
      var task = first(d, ['go', 'prev', 'refresh', 'noop']);
      if (!task || tag(task) === 'noop') return;
      if (tag(task) === 'go' && isExternal(attr(task, 'href'))) return;
      var type = (attr(d, 'type') || '').toLowerCase();
      var label = subst(attr(d, 'label') || '') || (type === 'prev' || tag(task) === 'prev' ? 'Назад' : type === 'accept' ? 'OK' : 'Меню');
      bar.appendChild(makeAction(document.createTextNode(label), task, 'softkey'));
    });
    bar.hidden = !bar.childNodes.length;

    // Timer: value is in tenths of a second.
    var timerEl = children(card, 'timer')[0];
    var timerTask = eventTask(card, 'ontimer');
    if (timerEl && timerTask) {
      var tenths = parseInt(subst(attr(timerEl, 'value') || '0'), 10);
      var tname = attr(timerEl, 'name');
      if (tname && Object.prototype.hasOwnProperty.call(vars, tname)) tenths = parseInt(getVar(tname), 10) || tenths;
      if (tenths > 0) timers.push(setTimeout(function () { run(timerTask); }, tenths * 100));
    }

    window.scrollTo(0, 0);
    if (forward) {
      var enter = eventTask(card, 'onenterforward');
      if (enter) setTimeout(function () { run(enter); }, 0);
    } else {
      var back = eventTask(card, 'onenterbackward');
      if (back) setTimeout(function () { run(back); }, 0);
    }
  }

  document.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    var el = accessKeys[e.key];
    if (el) { e.preventDefault(); el.click(); }
  });

  window.addEventListener('popstate', function (e) {
    var id = (e.state && e.state.card) || location.hash.slice(1);
    showCard(cardById(id) || cards[0], false);
  });

  if (!cards.length) {
    root.textContent = 'Пустой ответ сервера';
    return;
  }
  // Loading a deck is forward navigation unless the browser went back to it.
  var forwardNav = true;
  try {
    var nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
    if (nav && nav.type === 'back_forward') forwardNav = false;
  } catch (e) {}
  var startId = location.hash.slice(1);
  var start = (startId && cardById(startId)) || cards[0];
  history.replaceState({ card: attr(start, 'id') }, '', location.href);
  showCard(start, forwardNav);
})();
