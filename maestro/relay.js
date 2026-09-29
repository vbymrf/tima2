// Пересыльщик: слушает 127.0.0.1:<порт> в госте и льёт на тот же порт хоста.
// Нужен потому, что `adb forward` открывает сокет на машине adb-сервера (хост),
// а mobilecli опрашивает свой localhost. Прав администратора не требует.
const net = require("net");
const port = Number(process.argv[2]);
const host = process.argv[3];
net.createServer((c) => {
  const up = net.connect(port, host);
  c.pipe(up); up.pipe(c);
  c.on("error", () => {}); up.on("error", () => {});
}).listen(port, "127.0.0.1", () => console.log("пересылаю 127.0.0.1:" + port + " -> " + host + ":" + port));
