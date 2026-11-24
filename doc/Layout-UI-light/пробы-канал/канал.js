// Пробы ленты канала: ставит ленту на нужное место, отмечает прилипший низ записи и долю прочитанного,
// «текущую» запись (О-Д) и бледнеющие кнопки ленты (К3). Без этого пробы — картинки; с ним — прокручиваются живьём.
(function () {
  var feeds = Array.prototype.slice.call(document.querySelectorAll('.лента'));

  // data-scroll: "end" — к последней записи; "id:top:N" — верх записи в N точках от верха экрана;
  // "id:end:N" — низ записи в N точках от верха экрана; "id:part:P" — верх записи ушёл на P % её высоты.
  function place(f) {
    var s = f.dataset.scroll;
    if (!s) return;
    if (s === 'end') { f.scrollTop = f.scrollHeight; return; }
    var p = s.split(':'), el = f.querySelector('[data-id="' + p[0] + '"]');
    if (!el) return;
    var at = p[1] || 'top', n = +(p[2] || 0);
    var fr = f.getBoundingClientRect(), er = el.getBoundingClientRect();
    if (at === 'top') f.scrollTop += er.top - fr.top - n;
    else if (at === 'end') f.scrollTop += er.bottom - fr.top - n;
    else f.scrollTop += er.top - fr.top + er.height * n / 100;
  }

  function update(f) {
    var box = f.getBoundingClientRect();
    f.querySelectorAll('.низ-записи.липкий').forEach(function (n) {
      var r = n.getBoundingClientRect();
      var stuck = Math.abs(r.bottom - box.bottom) < 1.5;
      n.classList.toggle('прилип', stuck);
      var bar = n.querySelector('.доля');
      if (bar) {
        var rec = n.closest('.запись').getBoundingClientRect();
        var part = Math.min(1, Math.max(0, (box.bottom - rec.top) / rec.height));
        bar.style.width = (part * 100) + '%';
        bar.style.opacity = stuck ? 1 : 0;
      }
    });
    if (f.hasAttribute('data-focus')) {
      var mid = box.top + box.height / 2;
      f.querySelectorAll('.запись').forEach(function (z) {
        var r = z.getBoundingClientRect();
        z.classList.toggle('текущая', r.top <= mid && r.bottom > mid);
      });
    }
  }

  function wire(f) {
    place(f);
    update(f);
    f.addEventListener('scroll', function () { update(f); }, { passive: true });
    var field = f.closest('.поле-ленты');
    var fade = field && field.querySelector('.кнопки-ленты[data-fade]');
    if (!fade) return;
    var timer;
    var wake = function () {
      fade.classList.remove('бледно');
      clearTimeout(timer);
      timer = setTimeout(function () { fade.classList.add('бледно'); }, 2000);
    };
    f.addEventListener('scroll', wake, { passive: true });
    if (fade.dataset.fade === 'now') fade.classList.add('бледно');
  }

  feeds.forEach(wire);
  // Эмодзи и шрифт приходят позже разметки и меняют высоту строк — поставить ещё раз, когда всё загрузится.
  window.addEventListener('load', function () { feeds.forEach(function (f) { place(f); update(f); }); });
})();
