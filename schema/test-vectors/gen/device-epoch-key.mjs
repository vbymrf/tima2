// Вектор device_epoch_key — что ключ подписи устройства подписывает, публикуя ключ шифрования на
// эпоху (ПЛАН-(ПС) ПС3). Независимая от Go и Kotlin реализация: строка собирается здесь заново.
// Дописывает ОДИН вектор в vectors.json и не трогает остальные.
//
// Байты: "tima.device-epoch.v1|" + device_id + "|" + эпоха + "|" + base64url(ключ) без выравнивания.
//
// Запуск: node device-epoch-key.mjs
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const path = new URL('../vectors.json', import.meta.url);
const all = JSON.parse(readFileSync(path, 'utf8'));

const deviceId = '33333333-3333-3333-3333-333333333333';
const epoch = '2026-10';
const pub = Buffer.alloc(32, 0x5a);
const b64url = pub.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const bytes = Buffer.from('tima.device-epoch.v1|' + deviceId + '|' + epoch + '|' + b64url, 'utf8');

all.vectors.device_epoch_key = {
  desc: 'Подписываемые байты ключа шифрования устройства на эпоху (ПЛАН-(ПС) ПС3): "tima.device-epoch.v1|" + device_id + "|" + эпоха + "|" + base64url(encryption_pub) без выравнивания. Посчитан node (gen/device-epoch-key.mjs)',
  inputs: { device_id: deviceId, epoch, encryption_pub_hex: pub.toString('hex') },
  bytes_hex: bytes.toString('hex'),
  sha256_hex: createHash('sha256').update(bytes).digest('hex'),
};
writeFileSync(path, JSON.stringify(all, null, 2) + '\n');
console.log('device_epoch_key', all.vectors.device_epoch_key.sha256_hex);
