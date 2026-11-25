// Пробы ленты канала: ставит ленту на нужное место, отмечает прилипший низ записи и долю прочитанного,
// «текущую» запись (О-Д), бледнеющие кнопки ленты (К3) и панель «Статьи». Без этого пробы — картинки; с ним —
// прокручиваются живьём.
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
    // Будит прокрутка человеком, а не постановка ленты на место при загрузке: «в покое» начинается бледным
    ['wheel', 'touchstart', 'pointerdown', 'keydown'].forEach(function (ev) {
      f.addEventListener(ev, wake, { passive: true });
    });
    if (fade.dataset.fade === 'now') fade.classList.add('бледно');
    else wake();
  }

  feeds.forEach(wire);
  // Эмодзи и шрифт приходят позже разметки и меняют высоту строк — поставить ещё раз, когда всё загрузится.
  window.addEventListener('load', function () { feeds.forEach(function (f) { place(f); update(f); }); });

  // «Статья»: панель под лентой показывает отклики записи на экране (из её <template>), долю прочитанного у
  // длинной и номер. Кнопки: предыдущая и следующая — на запись, «вниз» — к последней, «назад» — откуда ушли.
  document.querySelectorAll('.панель-статьи').forEach(function (panel) {
    var f = panel.closest('.окно').querySelector('.лента.статьи');
    if (!f) return;
    var recs = Array.prototype.slice.call(f.querySelectorAll(':scope > .запись'));
    var row = panel.querySelector('.низ-записи'), bar = panel.querySelector('.доля');
    var num = panel.querySelector('.номер b'), back = [], cur = -1;
    function btn(go) { return panel.querySelector('[data-go="' + go + '"]'); }
    function current() {
      var box = f.getBoundingClientRect(), mid = box.top + box.height / 2;
      for (var i = 0; i < recs.length; i++) {
        var r = recs[i].getBoundingClientRect();
        if (r.top <= mid && r.bottom > mid) return i;
      }
      return recs.length - 1;
    }
    function topOf(i) { return f.scrollTop + recs[i].getBoundingClientRect().top - f.getBoundingClientRect().top; }
    function show() {
      var i = current(), box = f.getBoundingClientRect(), r = recs[i].getBoundingClientRect();
      if (i !== cur) {
        cur = i;
        row.innerHTML = recs[i].querySelector('template.отклики-записи').innerHTML;
        num.textContent = i + 1;
      }
      bar.style.opacity = r.height > box.height + 1 ? 1 : 0;
      bar.style.width = Math.min(100, Math.max(0, (box.bottom - r.top) / r.height * 100)) + '%';
      btn('prev').classList.toggle('нет', i === 0);
      btn('next').classList.toggle('нет', i === recs.length - 1);
      btn('end').classList.toggle('нет', i === recs.length - 1);
      btn('back').classList.toggle('нет', !back.length);
    }
    function go(top) { f.scrollTo({ top: top, behavior: 'smooth' }); }
    panel.addEventListener('click', function (e) {
      var a = e.target.closest('a');
      if (!a) return;
      e.preventDefault();
      var g = a.dataset.go;
      if (g === 'prev' && cur > 0) go(topOf(cur - 1));
      else if (g === 'next' && cur < recs.length - 1) go(topOf(cur + 1));
      else if (g === 'end') { back.push(f.scrollTop); go(topOf(recs.length - 1)); }
      else if (g === 'back' && back.length) go(back.pop());
      else if (a.classList.contains('кн-оценки')) {
        // «+» и «−» — одно из двух или ничего; запомнить в записи, чтобы при возврате к ней было то же
        var on = a.textContent === '+' ? 'да' : 'нет', was = a.classList.contains(on);
        row.querySelectorAll('.кн-оценки').forEach(function (b) { b.classList.remove('да', 'нет'); });
        if (!was) a.classList.add(on);
        recs[cur].querySelector('template.отклики-записи').innerHTML = row.innerHTML;
      }
      show();
    });
    f.addEventListener('scroll', show, { passive: true });
    show();
    window.addEventListener('load', show);
  });
})();
