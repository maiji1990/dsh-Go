// Shared by islands and DSH Web plugin slots. Layout only; no bridge access.
(function () {
  'use strict';
  if (window.DshGoMobileLayout) return;
  var ROLE = 'data-dsh-mobile-role', COMPACT = 'data-dsh-mobile-compact';
  var ROOT = 'data-dsh-mobile-root', WIDE = 'data-dsh-go-mobile-wide';
  var RULES = ['form.stack', 'toolbar.wrap', 'card-grid.collapse', 'table.scroll', 'dialog.fit', 'tab.scroll'];
  var ruleFor = { 'form-row': RULES[0], toolbar: RULES[1], 'card-grid': RULES[2], table: RULES[3], dialog: RULES[4], tablist: RULES[5] };
  function clock() { return window.performance ? window.performance.now() : Date.now(); }
  function control(el) { return /^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName); }
  function formControl(el) { return control(el) && !/^(checkbox|radio|hidden|range|color)$/.test(el.getAttribute('type') || ''); }
  function cardish(el) {
    if (el.children.length < 3 || /^(grid|treegrid|table|row|rowgroup)$/.test(el.getAttribute('role') || '')) return false;
    for (var i = 0; i < el.children.length; i++) {
      var k = el.children[i];
      if (!/^(ARTICLE|FIGURE)$/.test(k.tagName) && k.getAttribute('role') !== 'listitem' && !k.querySelector('h1,h2,h3,h4,h5,h6,[role=\"heading\"]')) return false;
      if (/^(TABLE|TR|TD|TH)$/.test(k.tagName) || /^(row|gridcell|columnheader|table)$/.test(k.getAttribute('role') || '') ||
          k.querySelector('table,[role="row"],[role="gridcell"]')) return false;
    }
    return true;
  }
  function classify(el, oldRole) {
    var role = el.getAttribute('role');
    if (el.tagName === 'TABLE') return el.querySelectorAll('th').length >= 5 || (el.rows && el.rows[0] && el.rows[0].cells.length >= 5) ? 'table' : null;
    if (el.tagName === 'DIALOG' || role === 'dialog' || el.getAttribute('aria-modal') === 'true') return 'dialog';
    if (role === 'tablist') return 'tablist';
    if (el.tagName === 'NAV' || role === 'navigation' || role === 'grid' || role === 'treegrid') return null;
    var cs = getComputedStyle(el), kids = el.children, labels = 0, inputs = 0, buttons = 0;
    for (var i = 0; i < kids.length; i++) {
      if (kids[i].tagName === 'LABEL') labels++;
      if (formControl(kids[i])) inputs++;
      if (kids[i].tagName === 'BUTTON') buttons++;
    }
    // An existing form role is independent of the column direction our CSS adds.
    if (cs.display === 'flex' && (cs.flexDirection.indexOf('row') === 0 || oldRole === 'form-row')) {
      if (labels && inputs) return 'form-row';
      if (buttons >= 2 && buttons === kids.length) return 'toolbar';
    }
    if (cs.display === 'grid' && cardish(el) && (oldRole === 'card-grid' || cs.gridTemplateColumns.trim().split(/\s+/).length >= 3)) return 'card-grid';
    return null;
  }
  function declaration(value) {
    if (!value || typeof value !== 'object' || value.v !== 1 ||
        ['automatic', 'pluginResponsive', 'compatibility', 'desktop'].indexOf(value.mode) < 0) return null;
    if ('compact' in value && (typeof value.compact !== 'number' || !Number.isFinite(value.compact) || value.compact < 240 || value.compact > 1024)) return null;
    if ('rules' in value && (value.rules === null || typeof value.rules !== 'object' || Array.isArray(value.rules))) return null;
    var rules = {};
    for (var i = 0; i < RULES.length; i++) {
      var rule = RULES[i];
      if (value.rules && rule in value.rules) {
        if (typeof value.rules[rule] !== 'boolean') return null;
        rules[rule] = value.rules[rule];
      }
    }
    return { mode: value.mode, compact: value.compact || 480, rules: rules };
  }
  function Controller(root, options) {
    this.root = root;
    this.options = options || {};
    this.owned = new Map();
    this.pending = new Set();
    this.walkers = [];
    this.running = false;
    this.frame = 0;
    this.generation = 0;
    this.failure = null;
    this.batches = 0;
    this.maxBatchNodes = 0;
    this.maxBatchMs = 0;
    this.visited = 0;
    this.lastTab = new WeakMap();
    this.onResize = this.resize.bind(this);
    this.onVisibility = this.visibility.bind(this);
    this.onFocus = this.focus.bind(this);
  }
  Controller.prototype.readDeclaration = function () {
    this.declared = null;
    if (this.failure !== 'rule-error') this.failure = null;
    if (this.options.overrideMode || this.options.mode === 'desktop') return;
    var nodes = this.root.hasAttribute('data-dsh-mobile-policy') ? [this.root] : this.root.querySelectorAll('[data-dsh-mobile-policy]');
    if (!nodes.length) return;
    if (nodes.length !== 1) { this.failure = 'ambiguous-declaration'; return; }
    try {
      var raw = nodes[0].getAttribute('data-dsh-mobile-policy');
      this.declared = raw.length <= 4096 ? declaration(JSON.parse(raw)) : null;
    } catch (_) {}
    if (!this.declared) this.failure = 'invalid-declaration';
  };
  Controller.prototype.mode = function () { return this.failure ? 'compatibility' : (this.options.overrideMode || (this.declared && this.declared.mode) || this.options.mode || 'automatic'); };
  Controller.prototype.mark = function (el, attr, value) {
    var attrs = this.owned.get(el);
    if (!attrs) { attrs = new Map(); this.owned.set(el, attrs); }
    if (!attrs.has(attr)) attrs.set(attr, el.getAttribute(attr));
    if (el.getAttribute(attr) !== value) el.setAttribute(attr, value);
  };
  Controller.prototype.unmark = function (el, attr) {
    var attrs = this.owned.get(el);
    if (!attrs || !attrs.has(attr)) return;
    var old = attrs.get(attr);
    if (old === null) el.removeAttribute(attr); else el.setAttribute(attr, old);
    attrs.delete(attr);
    if (!attrs.size) this.owned.delete(el);
  };
  Controller.prototype.release = function (el) {
    var attrs = this.owned.get(el);
    if (attrs) Array.from(attrs.keys()).forEach(function (a) { this.unmark(el, a); }, this);
  };
  Controller.prototype.start = function () {
    if (this.running || this.options.form === 'probe' || this.options.mode === 'desktop') return;
    this.readDeclaration();
    this.running = true;
    this.sizeKey = null;
    this.mark(this.root, ROOT, '');
    var self = this;
    this.mutations = new MutationObserver(function (records) {
      self.prune();
      records.forEach(function (r) {
        if (r.type === 'childList') {
          self.watchSize();
          self.enqueue(r.target, false);
          for (var i = 0; i < r.addedNodes.length; i++) if (r.addedNodes[i].nodeType === 1) self.enqueue(r.addedNodes[i], true);
        } else if (r.attributeName === 'data-dsh-mobile-policy') { self.update(self.options); } else {
          self.enqueue(r.target, true);
          if (r.attributeName === 'aria-selected') self.enqueue(r.target.closest('[role="tablist"]'), false);
        }
      });
    });
    this.mutations.observe(this.root, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['class', 'style', 'role', 'aria-modal', 'aria-selected', 'type', 'hidden', 'data-dsh-mobile-policy'] });
    if (window.ResizeObserver) {
      this.sizes = new ResizeObserver(this.onResize);
      this.sizes.observe(this.options.widthTarget || this.root);
      this.watchSize();
    }
    window.addEventListener('resize', this.onResize);
    document.addEventListener('visibilitychange', this.onVisibility);
    this.root.addEventListener('focusin', this.onFocus);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', this.onFocus);
    this.refresh();
  };
  Controller.prototype.watchSize = function () {
    if (!this.sizes) return;
    var node = this.root.firstElementChild;
    if (node === this.sizeNode) return;
    if (this.sizeNode) this.sizes.unobserve(this.sizeNode);
    this.sizeNode = node;
    if (node) this.sizes.observe(node);
  };
  Controller.prototype.enqueue = function (el, subtree) {
    if (!this.running || !el || el.nodeType !== 1 || !this.root.contains(el)) return;
    this.pending.add(el);
    if (subtree) {
      // TreeWalker advances lazily; never materialize a large querySelectorAll('*').
      // Coalesce overlapping walks so a mutation storm cannot multiply scans.
      for (var i = this.walkers.length - 1; i >= 0; i--) {
        if (this.walkers[i].currentNode === this.walkers[i].root && this.walkers[i].root.contains(el)) { this.schedule(); return; }
        if (el.contains(this.walkers[i].root)) this.walkers.splice(i, 1);
      }
      this.walkers.push(document.createTreeWalker(el, 1));
    }
    this.schedule();
  };
  Controller.prototype.prune = function () {
    var self = this;
    this.owned.forEach(function (_, el) { if (el !== self.root && !self.root.contains(el)) self.release(el); });
    this.pending.forEach(function (el) { if (!self.root.contains(el)) self.pending.delete(el); });
    this.walkers = this.walkers.filter(function (w) { return self.root.contains(w.root); });
  };
  Controller.prototype.resize = function () {
    var target = this.options.widthTarget || this.root;
    var key = target.clientWidth + ':' + (this.root.clientWidth || 0) + ':' + (this.root.firstElementChild ? this.root.firstElementChild.clientWidth : 0) + ':' + window.innerHeight;
    if (key === this.sizeKey) return;
    this.sizeKey = key;
    this.refresh();
  };
  Controller.prototype.refresh = function () {
    this.pending.clear();
    this.walkers = [];
    this.enqueue(this.root, true);
  };
  Controller.prototype.schedule = function () {
    if (!this.running || this.frame || document.hidden) return;
    var self = this, generation = this.generation;
    this.frame = requestAnimationFrame(function () { self.frame = 0; if (self.running && generation === self.generation) self.flush(); });
  };
  Controller.prototype.next = function () {
    if (this.pending.size) {
      var el = this.pending.values().next().value;
      this.pending.delete(el);
      return el;
    }
    while (this.walkers.length) {
      var node = this.walkers[0].nextNode();
      if (node) return node;
      this.walkers.shift();
    }
    return null;
  };
  Controller.prototype.flush = function () {
    var start = clock(), count = 0, el;
    var target = this.options.widthTarget || this.root;
    this.width = target.clientWidth || (target.parentElement && target.parentElement.clientWidth) || 0;
    var changes = [], tabs = [], self = this;
    try {
      // Read one bounded batch before writing attributes. Classification keeps
      // its semantic role while our own narrow-screen CSS changes direction.
      while (count < 200 && clock() - start < 8 && (el = this.next())) {
        if (!this.root.contains(el) || /^(SCRIPT|STYLE|LINK|IFRAME|CANVAS)$/.test(el.tagName)) continue;
        count++;
        this.visited++;
        var available = Math.min(this.width, (el.parentElement && el.parentElement.clientWidth) || this.width);
        var compact = available > 0 && available < (this.options.compact || (this.declared && this.declared.compact) || 480);
        var role = this.mode() === 'automatic' ? classify(el, el.getAttribute(ROLE)) : null;
        var rules = this.options.rules || (this.declared && this.declared.rules);
        if (role && rules && rules[ruleFor[role]] === false) role = null;
        changes.push({el: el, role: role,
          compact: !!role && ((compact && role !== 'table') || (role === 'dialog' && (el.scrollHeight > window.innerHeight - 16 || el.scrollWidth > this.width - 16))),
          wide: this.mode() !== 'desktop' && this.width > 0 && el.scrollWidth > this.width + 4 && el.clientWidth >= this.width - 8});
        if (compact && role === 'tablist') {
          var selected = el.querySelector('[aria-selected="true"]'), previous = this.lastTab.get(el);
          if (selected && (!previous || previous.selected !== selected || previous.width !== available)) tabs.push({el: el, selected: selected, width: available});
        }
      }
      changes.forEach(function (change) {
        if (change.role) self.mark(change.el, ROLE, change.role); else self.unmark(change.el, ROLE);
        if (change.compact) self.mark(change.el, COMPACT, ''); else self.unmark(change.el, COMPACT);
        if (change.wide) self.mark(change.el, WIDE, ''); else self.unmark(change.el, WIDE);
      });
      // One post-style geometry read phase, then scroll only the strips.
      tabs.forEach(function (tab) {
        var a = tab.selected.getBoundingClientRect(), b = tab.el.getBoundingClientRect();
        tab.left = tab.el.scrollLeft + a.left - b.left - (b.width - a.width) / 2;
      });
      tabs.forEach(function (tab) {
        tab.el.scrollLeft = tab.left;
        self.lastTab.set(tab.el, {selected: tab.selected, width: tab.width});
      });
    } catch (_) {
      // Never log exception strings: a plugin may include credentials in them.
      if (this.failure === 'rule-error') this.stop();
      else {
        this.failure = 'rule-error';
        this.update(Object.assign({}, this.options, { mode: 'compatibility' }));
      }
    }
    this.batches++;
    this.maxBatchNodes = Math.max(this.maxBatchNodes, count);
    this.maxBatchMs = Math.max(this.maxBatchMs, clock() - start);
    if (this.pending.size || this.walkers.length) this.schedule();
    else if (this.options.onDiagnostics) this.options.onDiagnostics(this.diagnostics());
  };
  Controller.prototype.focus = function () {
    var el = document.activeElement;
    if (!el || !this.root.contains(el) || !control(el)) return;
    var self = this, generation = this.generation;
    requestAnimationFrame(function () {
      if (!self.running || generation !== self.generation || !self.root.contains(el)) return;
      var r = el.getBoundingClientRect();
      var vv = window.visualViewport, top = vv ? vv.offsetTop : 0, bottom = top + (vv ? vv.height : window.innerHeight);
      if (r.bottom > bottom || r.top < top) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    });
  };
  Controller.prototype.visibility = function () {
    if (document.hidden) { if (this.frame) cancelAnimationFrame(this.frame); this.frame = 0; }
    else this.refresh();
  };
  Controller.prototype.update = function (options) {
    this.stop();
    this.options = options || {};
    this.start();
  };
  Controller.prototype.stop = function () {
    this.running = false;
    this.generation++;
    if (this.frame) cancelAnimationFrame(this.frame);
    this.frame = 0;
    if (this.mutations) this.mutations.disconnect();
    if (this.sizes) this.sizes.disconnect();
    this.sizeNode = null;
    window.removeEventListener('resize', this.onResize);
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.root.removeEventListener('focusin', this.onFocus);
    if (window.visualViewport) window.visualViewport.removeEventListener('resize', this.onFocus);
    Array.from(this.owned.keys()).forEach(this.release.bind(this));
    this.pending.clear();
    this.walkers = [];
    this.lastTab = new WeakMap();
  };
  Controller.prototype.diagnostics = function () {
    var roles = {}, self = this;
    this.owned.forEach(function (_, el) { var r = el.getAttribute(ROLE); if (r && self.root.contains(el)) roles[ruleFor[r]] = (roles[ruleFor[r]] || 0) + 1; });
    return { mode: this.mode(), width: this.width || 0, rules: roles, reason: this.failure,
      visited: this.visited, batches: this.batches, maxBatchNodes: this.maxBatchNodes, maxBatchMs: this.maxBatchMs,
      pending: this.pending.size + this.walkers.length, tracked: this.owned.size };
  };
  window.DshGoMobileLayout = { create: function (root, options) { return new Controller(root, options); }, rules: RULES, validateDeclaration: declaration };
})();
