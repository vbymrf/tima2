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

  // ── Меню записи, «Оценка», выбор нескольких, шапка (ПЛАН-(СЦ) §1.18, 8) ──────────────────────────
  var held = null, openedAt = 0;   // запись, о которой открыто меню; когда открыто — отпускание пальца не закрывает

  function sheetOf(win, kind) { return win.querySelector('.лист-поверх.' + kind); }
  function openSheet(s) { if (s) { s.classList.add('открыт'); openedAt = Date.now(); } }
  function closeSheet(s) { if (s) s.classList.remove('открыт'); }

  function openMenu(win, rec) {
    var s = sheetOf(win, 'меню-записи');
    if (!s || !rec) return;
    held = rec;
    // Абзацы — через пробел, иначе «Итоги недели» слипаются со следующей строкой
    var t = rec.querySelector('.текст-записи');
    var words = t ? Array.prototype.map.call(t.children.length ? t.children : [t], function (x) { return x.textContent; }).join(' ') : '';
    s.querySelector('.цитата').textContent = '«' + words.replace(/\s+/g, ' ').trim().slice(0, 160) + '»';
    var copy = s.querySelector('.копия .тянуть');
    if (copy) copy.textContent = rec.querySelector('.фото-записи') ? 'Сохранить как' : 'Скопировать текст';
    s.querySelectorAll('.подпункт').forEach(function (x) { x.classList.add('скрыт'); });
    var more = s.querySelector('.раскрыть .знак');
    if (more) more.textContent = '›';
    openSheet(s);
  }

  // «Оценка»: отмечена моя эмоция этой записи — та, что в плашке салатовая
  function openRate(win, block) {
    var s = sheetOf(win, 'оценка-лист');
    if (!s) return;
    var mine = block && block.querySelector('.эмоция.моя');
    var e = mine ? mine.textContent.trim().split(' ')[0] : null;
    pickEmotion(s, e && s.querySelector('.чип-шкалы[data-e="' + e + '"]'));
    openSheet(s);
  }
  function pickEmotion(s, chip) {
    s.querySelectorAll('.чип-шкалы').forEach(function (c) { c.classList.toggle('выбран', c === chip); });
    s.querySelector('.выбор-эмоции').textContent = chip ? chip.textContent : '—';
  }

  function countChosen(win) {
    var b = win.querySelector('.выбрано b');
    if (b) b.textContent = win.querySelectorAll('.запись.выбрана').length;
  }
  function startChoosing(win, rec) {
    var f = win.querySelector('.лента');
    f.classList.add('выбор');
    win.classList.add('выбор');
    if (rec) rec.classList.add('выбрана');
    countChosen(win);
  }
  function stopChoosing(win) {
    win.classList.remove('выбор');
    win.querySelector('.лента').classList.remove('выбор');
    win.querySelectorAll('.запись.выбрана').forEach(function (r) { r.classList.remove('выбрана'); });
  }

  // Палец вверх и вниз — одно из двух или ничего
  function thumb(a) {
    var on = a.dataset.v, was = a.classList.contains(on);
    a.parentNode.querySelectorAll('.кн-оценки').forEach(function (b) { b.classList.remove('да', 'нет'); });
    if (!was) a.classList.add(on);
  }

  document.addEventListener('click', function (e) {
    var win = e.target.closest('.окно');
    if (!win) return;
    var a = e.target.closest('a');
    var s = e.target.closest('.лист-поверх');
    if (s) {
      if (a && a.classList.contains('плитка-режима')) return;   // переход на другой вариант — ссылкой
      if (a) e.preventDefault();
      if (e.target === s) { if (Date.now() - openedAt > 400) closeSheet(s); return; }
      if (!a) return;
      if (a.hasAttribute('data-close')) closeSheet(s);
      else if (a.classList.contains('раскрыть')) {
        // «Изменить или удалить» раскрывается на месте, как у своего сообщения в группе
        var subs = s.querySelectorAll('.подпункт'), shut = subs[0].classList.contains('скрыт');
        subs.forEach(function (x) { x.classList.toggle('скрыт', !shut); });
        a.querySelector('.знак').textContent = shut ? '⌄' : '›';
      } else if (a.classList.contains('выбрать')) { closeSheet(s); startChoosing(win, held); }
      else if (a.classList.contains('флажок')) {
        var on = !a.classList.contains('вкл');
        if (a.dataset.toggle === 'head-btn') {
          // Одна настройка на все каналы — значок появляется во всех шапках сразу
          document.querySelectorAll('[data-toggle="head-btn"]').forEach(function (x) { x.classList.toggle('вкл', on); });
          document.querySelectorAll('.кн-режима').forEach(function (x) { x.classList.toggle('скрыта', !on); });
        } else a.classList.toggle('вкл', on);
      } else if (a.classList.contains('чип-шкалы')) pickEmotion(s, a.classList.contains('выбран') ? null : a);
      else if (a.hasAttribute('data-unset')) pickEmotion(s, null);
      else if (a.classList.contains('пункт-меню')) closeSheet(s);
      return;
    }
    var f = win.querySelector('.лента');
    if (f && f.classList.contains('выбор')) {
      if (a && a.hasAttribute('data-sel-close')) { e.preventDefault(); stopChoosing(win); return; }
      if (a && a.classList.contains('чип-выбора')) { e.preventDefault(); stopChoosing(win); return; }
      var rec = e.target.closest('.лента .запись');
      if (rec) {
        e.preventDefault();
        rec.classList.toggle('выбрана');
        countChosen(win);
        return;
      }
    }
    if (a && a.classList.contains('кн-настроек')) { e.preventDefault(); openSheet(sheetOf(win, 'меню-шапки')); return; }
    var emo = e.target.closest('.секция.эмоции-слева[data-open]');
    if (emo) { e.preventDefault(); openRate(win, emo.closest('.блок-откликов')); return; }
    if (a && a.classList.contains('кн-оценки') && a.dataset.v && !a.closest('.панель-статьи')) { e.preventDefault(); thumb(a); }
  });

  // «Лента»: меню — длительным нажатием на запись (полсекунды без сдвига)
  document.querySelectorAll('.лента.с-меню').forEach(function (f) {
    var timer = null, sx = 0, sy = 0;
    function cancel() { clearTimeout(timer); timer = null; }
    f.addEventListener('pointerdown', function (e) {
      var rec = e.target.closest('.запись');
      if (f.classList.contains('выбор') || !rec || e.target.closest('a, .секция.эмоции-слева')) return;
      sx = e.clientX; sy = e.clientY;
      cancel();
      timer = setTimeout(function () { timer = null; openMenu(f.closest('.окно'), rec); }, 500);
    });
    f.addEventListener('pointermove', function (e) { if (Math.abs(e.clientX - sx) + Math.abs(e.clientY - sy) > 8) cancel(); });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (ev) { f.addEventListener(ev, cancel); });
    f.addEventListener('scroll', cancel, { passive: true });
    f.addEventListener('contextmenu', function (e) { if (e.target.closest('.запись')) e.preventDefault(); });
  });

  // «Статья»: панель под лентой показывает отклики записи на экране (из её <template>) и долю прочитанного у
  // длинной. Нижняя строка: «⋯» — меню записи; «назад» — появляется, когда есть куда вернуться; предыдущая и
  // следующая — на запись; число новых и «вниз» — к последней. Номера записи нет.
  document.querySelectorAll('.панель-статьи').forEach(function (panel) {
    var win = panel.closest('.окно'), f = win.querySelector('.лента.статьи');
    if (!f) return;
    var recs = Array.prototype.slice.call(f.querySelectorAll(':scope > .запись'));
    var row = panel.querySelector('.низ-записи'), bar = panel.querySelector('.доля');
    var fresh = panel.querySelector('.сколько'), back = panel.hasAttribute('data-back') ? [0] : [], cur = -1;
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
      var i = current(), box = f.getBoundingClientRect(), r = recs[i].getBoundingClientRect(), last = i === recs.length - 1;
      if (i !== cur) {
        cur = i;
        row.innerHTML = recs[i].querySelector('template.отклики-записи').innerHTML;
        fresh.querySelector('b').textContent = recs.length - 1 - i;
      }
      bar.style.opacity = r.height > box.height + 1 ? 1 : 0;
      bar.style.width = Math.min(100, Math.max(0, (box.bottom - r.top) / r.height * 100)) + '%';
      btn('prev').classList.toggle('нет', i === 0);
      btn('next').classList.toggle('нет', last);
      btn('end').classList.toggle('скрыта', last);
      fresh.classList.toggle('скрыта', last);
      btn('back').classList.toggle('скрыта', !back.length);
    }
    function go(top) { f.scrollTo({ top: top, behavior: 'smooth' }); }
    panel.addEventListener('click', function (e) {
      var a = e.target.closest('a');
      if (!a || a.getAttribute('href') !== '#') return;   // комментарии — обычной ссылкой
      e.preventDefault();
      var g = a.dataset.go;
      if (g === 'menu') openMenu(win, recs[cur]);
      else if (g === 'prev' && cur > 0) go(topOf(cur - 1));
      else if (g === 'next' && cur < recs.length - 1) go(topOf(cur + 1));
      else if (g === 'end') { back.push(f.scrollTop); go(topOf(recs.length - 1)); }
      else if (g === 'back' && back.length) go(back.pop());
      else if (a.classList.contains('кн-оценки') && a.dataset.v) {
        // Запомнить в записи, чтобы при возврате к ней было то же
        thumb(a);
        recs[cur].querySelector('template.отклики-записи').innerHTML = row.innerHTML;
      }
      show();
    });
    f.addEventListener('scroll', show, { passive: true });
    show();
    window.addEventListener('load', show);
  });
})();
