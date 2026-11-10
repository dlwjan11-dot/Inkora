/* plays the shortcut cards made by tools/shortcut-demos/make.py */
(function () {
  var COL = { ink: '#E8E9ED', accent: '#6E9FF5', accentsoft: '#2b3d63', dim: '#5a5f6c', paper: '#F2F1EC', gold: '#FFBE3D', red: '#F0706A', green: '#6FCF97', panel: '#1b1d24', panel2: '#2c2f3a' };
  function val(v, t) {
    if (typeof v === 'number') return v;
    if (t <= v[0][0]) return v[0][1];
    for (var i = 1; i < v.length; i++) if (t <= v[i][0]) {
      var a = v[i - 1], b = v[i], u = (t - a[0]) / Math.max(1e-6, b[0] - a[0]);
      u = u * u * (3 - 2 * u);
      return a[1] + (b[1] - a[1]) * u;
    }
    return v[v.length - 1][1];
  }
  function draw(g, d, t, W) {
    var k = W / 100;
    g.setTransform(k, 0, 0, k, 0, 0);
    g.clearRect(0, 0, 100, 60);
    d.els.forEach(function (e) {
      var a = val(e.a == null ? 1 : e.a, t);
      if (a <= 0.004) return;
      g.globalAlpha = Math.min(1, a);
      var c = COL[e.c] || COL.ink;
      g.fillStyle = c; g.strokeStyle = c; g.lineCap = 'round'; g.lineJoin = 'round';
      if (e.k === 'rect') {
        var w = val(e.w, t), h = val(e.h, t);
        g.save(); g.translate(val(e.x, t), val(e.y, t)); g.rotate(val(e.rot || 0, t) * Math.PI / 180);
        var r = Math.min(e.r || 0, w / 2, h / 2);
        g.beginPath(); g.roundRect(-w / 2, -h / 2, w, h, r);
        if (e.s) { g.lineWidth = e.s; g.stroke(); } else g.fill();
        g.restore();
      } else if (e.k === 'circ') {
        g.beginPath(); g.arc(val(e.x, t), val(e.y, t), Math.max(0.01, val(e.r, t)), 0, 7);
        if (e.s) { g.lineWidth = e.s; g.stroke(); } else g.fill();
      } else if (e.k === 'line') {
        g.lineWidth = e.s; g.beginPath(); g.moveTo(val(e.x1, t), val(e.y1, t)); g.lineTo(val(e.x2, t), val(e.y2, t)); g.stroke();
      } else if (e.k === 'poly') {
        var p = val(e.p == null ? 1 : e.p, t), n = e.pts.length - 1, upto = p * n;
        if (upto <= 0) return;
        g.lineWidth = e.s; g.beginPath(); g.moveTo(e.pts[0][0], e.pts[0][1]);
        for (var i = 1; i <= n; i++) {
          if (i <= upto) g.lineTo(e.pts[i][0], e.pts[i][1]);
          else { var f = upto - (i - 1); if (f > 0) g.lineTo(e.pts[i - 1][0] + (e.pts[i][0] - e.pts[i - 1][0]) * f, e.pts[i - 1][1] + (e.pts[i][1] - e.pts[i - 1][1]) * f); break; }
        }
        g.stroke();
      } else if (e.k === 'finger') {
        var x = val(e.x, t), y = val(e.y, t), dn = val(e.d == null ? 1 : e.d, t) > 0.5;
        g.fillStyle = '#ffffff'; g.strokeStyle = '#ffffff';
        g.globalAlpha = Math.min(1, a) * (dn ? 0.92 : 0.45);
        g.beginPath(); g.arc(x, y, 3.6, 0, 7); g.fill();
        g.globalAlpha = Math.min(1, a) * (dn ? 0.4 : 0.15);
        g.lineWidth = 1.1; g.beginPath(); g.arc(x, y, 5.6, 0, 7); g.stroke();
        g.globalAlpha = Math.min(1, a) * 0.5; g.strokeStyle = '#000'; g.lineWidth = 0.4; g.beginPath(); g.arc(x, y, 3.6, 0, 7); g.stroke();
      } else if (e.k === 'pen') {
        var px = val(e.x, t), py = val(e.y, t), b = val(e.b || 0, t) > 0.5;
        var ex = px + 15, ey = py - 21;
        g.strokeStyle = '#11131a'; g.lineWidth = 4.6; g.beginPath(); g.moveTo(px + 2.2, py - 3.1); g.lineTo(ex, ey); g.stroke();
        g.strokeStyle = '#d5d8e0'; g.lineWidth = 3.4; g.beginPath(); g.moveTo(px + 2.2, py - 3.1); g.lineTo(ex, ey); g.stroke();
        g.lineCap = 'butt'; g.strokeStyle = b ? COL.accent : '#7a7f8c'; g.lineWidth = 3.4;
        g.beginPath(); g.moveTo(px + 5.6, py - 7.8); g.lineTo(px + 8.2, py - 11.5); g.stroke(); g.lineCap = 'round';
        g.fillStyle = '#d5d8e0'; g.beginPath(); g.moveTo(px, py); g.lineTo(px + 3.6, py - 2.2); g.lineTo(px + 0.9, py - 4.1); g.closePath(); g.fill();
      } else if (e.k === 'txt') {
        g.font = '600 ' + e.z + 'px system-ui, sans-serif'; g.textAlign = 'center'; g.fillText(e.t, val(e.x, t), val(e.y, t));
      }
    });
    g.globalAlpha = 1;
  }
  window.InkoraDemos = { draw: draw, val: val };
})();
