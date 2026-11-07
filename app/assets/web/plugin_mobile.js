// DSH Web host for the shared controller. Official navigation/modal stay owned by DSH.
(function () {
  'use strict';
  if (window.__dshGoPluginMobile) return;
  var selector = '[data-slot="settings.section"],[data-slot="settings.general.item"],[data-slot^="plugins."]';
  var roots = new Set(), controllers = new Map(), policies = new Map();
  var config = { enabled: true, modes: {} }, observer, scheduled = false, stopped = false;
  function discover(node) {
    if (!node || node.nodeType !== 1) return;
    if (node.matches(selector)) roots.add(node);
    node.querySelectorAll(selector).forEach(function (root) { roots.add(root); });
  }
  function displayed(root) {
    if (root.closest('[hidden],[inert]')) return false;
    var cs = getComputedStyle(root);
    if (cs.display === 'none' || cs.visibility === 'hidden') return false;
    if (root.getClientRects().length) return true;
    // Slot outlets are display:contents; check their first real box instead.
    var stack = Array.from(root.children);
    while (stack.length) {
      var child = stack.shift();
      if (child.getClientRects().length) return true;
      if (getComputedStyle(child).display === 'contents') stack.push.apply(stack, Array.from(child.children));
    }
    return false;
  }
  function policy(root) {
    var modes = [], explicit = false, bridge = window.__dshGoBridge;
    // List slots have no per-occupant DOM marker: never guess ownership.
    if (bridge && bridge.occupants) {
      try {
        bridge.occupants(root.getAttribute('data-slot')).filter(function (o) { return !o.official && !o.masked; }).forEach(function (o) {
          var override = config.modes[o.package];
          if (override) explicit = true;
          modes.push(override || 'automatic');
        });
      } catch (_) { modes = ['compatibility']; }
    }
    if (!modes.length && Object.keys(config.modes).length) modes = ['compatibility'];
    var distinct = Array.from(new Set(modes));
    var mode = !config.enabled ? 'desktop' : distinct.length > 1 ? 'compatibility' : distinct[0] || 'automatic';
    return { mode: mode, overrideMode: explicit || mode === 'compatibility' ? mode : null };
  }
  function sync() {
    scheduled = false;
    if (stopped) return;
    var enabled = document.documentElement.hasAttribute('data-dsh-app-layout') && config.enabled;
    roots.forEach(function (root) { if (!root.isConnected || !root.matches(selector)) roots.delete(root); });
    controllers.forEach(function (controller, root) {
      if (!enabled || !roots.has(root) || !displayed(root)) {
        controller.stop(); controllers.delete(root); policies.delete(root);
      }
    });
    if (!enabled || !window.DshGoMobileLayout) return;
    roots.forEach(function (root) {
      if (!displayed(root) || (root.parentElement && root.parentElement.closest(selector))) return;
      var next = policy(root), key = JSON.stringify(next);
      if (!controllers.has(root)) {
        var controller = window.DshGoMobileLayout.create(root, next);
        controllers.set(root, controller); policies.set(root, key); controller.start();
      } else if (policies.get(root) !== key) {
        policies.set(root, key); controllers.get(root).update(next);
      }
    });
  }
  function schedule() {
    if (scheduled || stopped) return;
    scheduled = true;
    requestAnimationFrame(sync);
  }
  function start() {
    stopped = false;
    discover(document.documentElement);
    observer = new MutationObserver(function (records) {
      records.forEach(function (r) {
        if (r.type === 'childList') {
          for (var i = 0; i < r.addedNodes.length; i++) discover(r.addedNodes[i]);
        } else if (r.attributeName === 'data-slot') discover(r.target);
      });
      schedule();
    });
    observer.observe(document.documentElement, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['data-dsh-app-layout', 'data-slot', 'class', 'style', 'hidden', 'inert'] });
    sync();
  }
  function stop() {
    stopped = true;
    if (observer) observer.disconnect();
    controllers.forEach(function (c) { c.stop(); });
    controllers.clear(); policies.clear(); roots.clear();
  }
  window.__dshGoPluginMobile = {
    configure: function (next) {
      config = {enabled: !next || next.enabled !== false, modes: (next && next.modes) || {}};
      sync();
    },
    stop: stop,
    inspect: function () { return Array.from(controllers.values()).map(function (c) { return c.diagnostics(); }); },
  };
  window.addEventListener('pagehide', stop);
  window.addEventListener('pageshow', function () { if (stopped) start(); });
  start();
})();
