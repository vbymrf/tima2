// Вектор group_message_canonical_v2 — привязка ключа у сообщения группы (ADR-0013, решение
// заказчика 2026-10-06 «отменяем решение 5»). Независимая от Go и Kotlin реализация: только
// node:crypto (SHA-256, HKDF). Дописывает ОДИН вектор в vectors.json и не трогает остальные —
// group_message_canonical и canonical_bytes_v2 внесены не генератором, и полная регенерация
// generate.mjs их бы стёрла.
//
// Раскладка v2 = раскладка v1 с доменом tima.group_message.v2 ⊕ key_commitment (32 байта),
// key_commitment = HKDF-SHA256(ikm = ключ группы, salt пустой, info "tima/commit/v1", 32).
// Ключ группы — ключ вектора secretbox: им и закрыт payload.
//
// Запуск: node group-message-v2.mjs
import { createHash, hkdfSync } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const path = new URL('../vectors.json', import.meta.url);
const all = JSON.parse(readFileSync(path, 'utf8'));
const v1 = all.vectors.group_message_canonical.inputs;
const sb = all.vectors.secretbox;

const lp = (s) => {
  const b = Buffer.from(s, 'utf8');
  const n = Buffer.alloc(4);
  n.writeUInt32LE(b.length);
  return Buffer.concat([n, b]);
};
const u32 = (x) => { const b = Buffer.alloc(4); b.writeUInt32LE(x); return b; };
const u64 = (x) => { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(x)); return b; };

const domain = 'tima.group_message.v2';
const groupKey = Buffer.from(sb.key, 'hex');
const payload = Buffer.from(sb.kodium_output_hex, 'hex');
const commitment = Buffer.from(hkdfSync('sha256', groupKey, Buffer.alloc(0), Buffer.from('tima/commit/v1'), 32));

const canonical = Buffer.concat([
  lp(domain), lp(v1.group_id), lp(v1.sender_id), lp(v1.sender_device),
  u32(v1.kind), u64(v1.created_at_unix_ms), u64(v1.thread_root), u64(v1.reply_to), u32(v1.gk_version),
  createHash('sha256').update(payload).digest(),
  commitment,
]);

all.vectors.group_message_canonical_v2 = {
  desc: 'Раскладка версии 2 (ADR-0013 для групп): та же, что group_message_canonical, с доменом tima.group_message.v2 и key_commitment ХВОСТОМ; key_commitment = HKDF-SHA256(ключ группы, salt пустой, "tima/commit/v1", 32); ключ группы — key из secretbox, payload — kodium_output_hex из secretbox. Посчитан node:crypto (gen/group-message-v2.mjs)',
  inputs: { ...v1, domain, group_key_from: 'secretbox.key' },
  key_commitment_hex: commitment.toString('hex'),
  canonical_bytes_hex: canonical.toString('hex'),
  sha256_hex: createHash('sha256').update(canonical).digest('hex'),
};
writeFileSync(path, JSON.stringify(all, null, 2) + '\n');
console.log('group_message_canonical_v2', all.vectors.group_message_canonical_v2.sha256_hex);
