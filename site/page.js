// The results page's script: sorting only. Every number on screen is in the page as the
// generator wrote it; this script reorders rows and never computes or formats a figure.
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

  var tables = document.querySelectorAll("table.sortable");
  Array.prototype.forEach.call(tables, function (table) {
    var heads = table.tHead.rows[0].cells;
    Array.prototype.forEach.call(heads, function (th, col) {
      var b = th.querySelector("button");
      if (b) b.addEventListener("click", function () { sortBy(table, col, th); });
    });
  });
})();
