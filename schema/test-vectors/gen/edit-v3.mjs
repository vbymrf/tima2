// Векторы версии 3 — правка сообщения (ADR-0045, решение заказчика 2026-10-18: «Правку делаем
// новой версией конверта»). Независимая от Go и Kotlin реализация: только node:crypto
// (SHA-256, HKDF). Дописывает ДВА вектора в vectors.json и не трогает остальные — полная
// регенерация generate.mjs стёрла бы векторы, внесённые не им.
//
// canonical_bytes_v3 (личное): раскладка v2 (format_version = 3) ⊕ key_commitment (32 байта)
// ⊕ u64(edit_of). Блобы пустые — как у вектора v2: проверяется хвост, а не полезная нагрузка.
//
// group_message_canonical_v3: раскладка группы с доменом tima.group_message.v3 ⊕ u64(edit_of)
// ⊕ key_commitment (у личной группы 32 байта; у публичной — пусто). Здесь — личная группа:
// ключ группы и payload — из вектора secretbox, как у v2.
//
// Запуск: node edit-v3.mjs
import { createHash, hkdfSync } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const path = new URL('../vectors.json', import.meta.url);
const all = JSON.parse(readFileSync(path, 'utf8'));

const lp = (s) => {
  const b = Buffer.from(s, 'utf8');
  const n = Buffer.alloc(4);
  n.writeUInt32LE(b.length);
  return Buffer.concat([n, b]);
};
const u32 = (x) => { const b = Buffer.alloc(4); b.writeUInt32LE(x); return b; };
const u64 = (x) => { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(x)); return b; };
const sha256 = (b) => createHash('sha256').update(b).digest();
const commit = (key) => Buffer.from(hkdfSync('sha256', key, Buffer.alloc(0), Buffer.from('tima/commit/v1'), 32));
const empty = Buffer.alloc(0);

// ── личное: canonical_bytes_v3 ──
{
  const v2 = all.vectors.canonical_bytes_v2.inputs;
  const inputs = { ...v2, format_version: 3, message_id: 43, edit_of: 42 };
  const commitment = commit(Buffer.from(inputs.message_key_hex, 'hex'));
  const canonical = Buffer.concat([
    u32(inputs.format_version), u64(inputs.message_id),
    lp(inputs.chat_id), lp(inputs.sender_id), lp(inputs.sender_device),
    u32(inputs.kind), u64(inputs.created_at_unix_ms), u64(inputs.reply_to),
    sha256(empty), sha256(empty), sha256(empty), sha256(empty),
    commitment,
    u64(inputs.edit_of),
  ]);
  all.vectors.canonical_bytes_v3 = {
    desc: 'Раскладка версии 3 (ADR-0045, правка): та же, что v2, с format_version = 3, ХВОСТОМ — key_commitment и u64(edit_of). Блобы пустые. Посчитан node:crypto (gen/edit-v3.mjs)',
    inputs,
    key_commitment_hex: commitment.toString('hex'),
    canonical_bytes_hex: canonical.toString('hex'),
    sha256_hex: sha256(canonical).toString('hex'),
  };
  console.log('canonical_bytes_v3', all.vectors.canonical_bytes_v3.sha256_hex);
}

// ── группа: group_message_canonical_v3 ──
{
  const v1 = all.vectors.group_message_canonical.inputs;
  const sb = all.vectors.secretbox;
  const domain = 'tima.group_message.v3';
  const editOf = 7;
  const groupKey = Buffer.from(sb.key, 'hex');
  const payload = Buffer.from(sb.kodium_output_hex, 'hex');
  const commitment = commit(groupKey);
  const canonical = Buffer.concat([
    lp(domain), lp(v1.group_id), lp(v1.sender_id), lp(v1.sender_device),
    u32(v1.kind), u64(v1.created_at_unix_ms), u64(v1.thread_root), u64(v1.reply_to), u32(v1.gk_version),
    sha256(payload),
    u64(editOf),
    commitment,
  ]);
  all.vectors.group_message_canonical_v3 = {
    desc: 'Раскладка версии 3 группы (ADR-0045, правка): домен tima.group_message.v3, поля как у v1, затем u64(edit_of), затем key_commitment (личная группа — 32 байта; у публичной пусто). Ключ группы и payload — из secretbox. Посчитан node:crypto (gen/edit-v3.mjs)',
    inputs: { ...v1, domain, edit_of: editOf, group_key_from: 'secretbox.key' },
    key_commitment_hex: commitment.toString('hex'),
    canonical_bytes_hex: canonical.toString('hex'),
    sha256_hex: sha256(canonical).toString('hex'),
  };
  console.log('group_message_canonical_v3', all.vectors.group_message_canonical_v3.sha256_hex);
}

writeFileSync(path, JSON.stringify(all, null, 2) + '\n');
