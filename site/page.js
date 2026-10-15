// The results page's script: sorting, and switching a figure among the settings the generator drew it
// at. Every number on screen is in the page, or in a fragment of it, as the generator wrote it; this
// script reorders rows and swaps whole fragments, and never computes or formats a figure.
(function () {
  "use strict";
  document.documentElement.classList.add("js");

  function key(cell) {
    var v = cell ? cell.getAttribute("data-v") : "";
    if (v === null || v === "") return { empty: true };
    var n = Number(v);
    return isNaN(n) ? { text: v.toLowerCase() } : { num: n };
  }

  function compare(a, b) {
    if (a.empty || b.empty) return a.empty === b.empty ? 0 : a.empty ? 1 : -1;
    if (a.num !== undefined && b.num !== undefined) return a.num - b.num;
    var x = a.text !== undefined ? a.text : String(a.num);
    var y = b.text !== undefined ? b.text : String(b.num);
    return x < y ? -1 : x > y ? 1 : 0;
  }

  function sortBy(table, col, th) {
    var body = table.tBodies[0];
    var rows = Array.prototype.slice.call(body.rows);
    var dir = th.getAttribute("aria-sort") === "descending" ? "ascending" : "descending";
    rows.sort(function (r, s) {
      var c = compare(key(r.cells[col]), key(s.cells[col]));
      return dir === "ascending" ? c : -c;
    });
    var heads = table.tHead.rows[0].cells;
    for (var i = 0; i < heads.length; i++) heads[i].removeAttribute("aria-sort");
    th.setAttribute("aria-sort", dir);
    rows.forEach(function (r) { body.appendChild(r); });
  }

  // A view box (SiteBuild.views): the page holds the default view; every other setting the data has
  // is a fragment under assets/views/, fetched when a control asks for it and put in its place.
  // An option whose setting, with the others as chosen, has no fragment is disabled.
  Array.prototype.forEach.call(document.querySelectorAll("div.views"), function (box) {
    var have = (box.getAttribute("data-views") || "").split(" ");
    var view = box.querySelector("div.view");
    var status = box.querySelector(".vstatus");
    var radios = box.querySelectorAll(".controls input[type=radio]");
    var axes = ["pen", "spread", "wong"];
    var shown = view.getAttribute("data-view");
    var want = shown;
    var cache = {};
    cache[shown] = view.innerHTML;

    function chosen(axis, value) {
      return axes.map(function (a) {
        if (a === axis) return value;
        var c = box.querySelector(".controls input[data-axis=\"" + a + "\"]:checked");
        return c ? c.value : "";
      }).join("-");
    }
    function mark() {
      Array.prototype.forEach.call(radios, function (r) {
        var ok = have.indexOf(chosen(r.getAttribute("data-axis"), r.value)) >= 0;
        r.disabled = !ok;
        r.parentNode.className = ok ? "" : "na";
      });
    }
    function select(name) {
      var parts = name.split("-");
      Array.prototype.forEach.call(radios, function (r) {
        r.checked = r.value === parts[axes.indexOf(r.getAttribute("data-axis"))];
      });
      mark();
    }
    function show(name, html) {
      view.innerHTML = html;
      view.setAttribute("data-view", name);
      view.removeAttribute("aria-busy");
      status.textContent = "";
      shown = name;
    }
    function go() {
      var name = chosen(null, null);
      if (have.indexOf(name) < 0) { select(shown); return; }
      mark();
      want = name;
      if (cache[name] !== undefined) { show(name, cache[name]); return; }
      view.setAttribute("aria-busy", "true");
      fetch(box.getAttribute("data-src") + name + ".html?v=" + box.getAttribute("data-v")).then(function (r) {
        if (!r.ok) throw new Error(String(r.status));
        return r.text();
      }).then(function (t) {
        cache[name] = t;
        if (want === name) show(name, t);
      }, function () {
        if (want !== name) return;
        view.removeAttribute("aria-busy");
        status.textContent = box.getAttribute("data-fail");
        want = shown;
        select(shown);
      });
    }
    Array.prototype.forEach.call(radios, function (r) { r.addEventListener("change", go); });
    mark();
  });

  var tables = document.querySelectorAll("table.sortable");
  Array.prototype.forEach.call(tables, function (table) {
    var heads = table.tHead.rows[0].cells;
    Array.prototype.forEach.call(heads, function (th, col) {
      var b = th.querySelector("button");
      if (b) b.addEventListener("click", function () { sortBy(table, col, th); });
    });
  });
})();
