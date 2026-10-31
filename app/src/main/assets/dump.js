/*
 * Chẩn đoán: trạng thái script ZaloDuo + khung giao diện trang Zalo (loại thẻ, id, class, vị trí, kích thước).
 * KHÔNG lấy nội dung tin nhắn, tên người hay giá trị dữ liệu. Chạy độc lập, kể cả khi shim.js không chạy.
 */
(function () {
  var zw = window.__zw;
  var mobile = null;
  try { mobile = zw && zw.mobileInfo ? JSON.parse(zw.mobileInfo()) : null; } catch (e) { mobile = String(e); }
  var info = {
    shim: zw ? 'v' + (zw.version || '?') : 'KHONG CHAY',
    bridge: !!window.ZaloBridge,
    mobile: mobile,
    notification: typeof Notification === 'undefined' ? 'none' : Notification.permission,
    hidden: document.hidden,
    viewport: (document.querySelector('meta[name="viewport"]') || {}).content || '(none)',
    size: innerWidth + 'x' + innerHeight + ' scroll=' + document.documentElement.scrollWidth,
    titleNumbers: ((document.title || '').match(/\d+/g) || []).join(','),
    url: location.host + location.pathname,
    ua: navigator.userAgent
  };
  var out = [];
  function walk(el, depth) {
    if (depth > 18 || out.length > 900) return;
    var r = el.getBoundingClientRect();
    if (r.width < 24 || r.height < 16) {
      // vẫn đi xuyên lớp bọc cao 0px
      if (!(r.height === 0 && el.children.length)) return;
    }
    var cls = typeof el.className === 'string'
      ? el.className.trim().split(/\s+/).filter(Boolean).slice(0, 4) : [];
    var attrs = [];
    for (var i = 0; i < el.attributes.length; i++) {
      var n = el.attributes[i].name;
      if (n === 'data-zd') attrs.push('data-zd=' + el.attributes[i].value);
      else if (n === 'type' && el.tagName === 'INPUT') attrs.push('type=' + el.attributes[i].value);
      else if (n.indexOf('data-') === 0 || n === 'role' || n === 'contenteditable' || n === 'placeholder') attrs.push(n);
    }
    var cs = getComputedStyle(el);
    var pos = cs.position !== 'static' ? ' ' + cs.position : '';
    var disp = cs.display === 'flex' || cs.display === 'grid' ? ' ' + cs.display + (cs.display === 'flex' ? ':' + cs.flexDirection : '') : '';
    out.push(new Array(depth + 1).join('  ') + el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') +
      (cls.length ? '.' + cls.join('.') : '') +
      ' [' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + ']' +
      pos + disp + (attrs.length ? ' {' + attrs.join(',') + '}' : ''));
    var kids = el.children;
    for (var k = 0; k < kids.length && k < 4; k++) walk(kids[k], depth + 1);
    if (kids.length > 4) out.push(new Array(depth + 2).join('  ') + '... +' + (kids.length - 4));
  }
  if (document.body) walk(document.body, 0);
  return JSON.stringify(info) + '\n' + out.join('\n');
})()
