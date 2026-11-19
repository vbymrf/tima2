// Вектор entity_transfer — акт передачи владения сущностью Социума (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6, СЦ11).
// Независимая от Go и Kotlin реализация: строка собирается здесь заново, подпись — tweetnacl (эталон Kodium).
// Дописывает ОДИН вектор в vectors.json и не трогает остальные.
//
// Байты: "tima.entity-transfer.v1|" + вид + "|" + номер сущности + "|" + номер следующего звена (десятичное)
//        + "|" + от кого (user_id) + "|" + кому (user_id) + "|" + срок (секунды Unix, десятичное).
// Подписывают одни и те же байты оба: владелец — ключом последнего звена, принимающий — своим ключом личности.
//
// Запуск: node entity-transfer.mjs
import nacl from 'tweetnacl';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const path = new URL('../vectors.json', import.meta.url);
const all = JSON.parse(readFileSync(path, 'utf8'));

const kind = 'group';
const entityId = '44444444-4444-4444-4444-444444444444';
const link = 2;
const from = '55555555-5555-5555-5555-555555555555';
const to = '66666666-6666-6666-6666-666666666666';
const expires = 1795000000;
const bytes = Buffer.from(
  'tima.entity-transfer.v1|' + kind + '|' + entityId + '|' + link + '|' + from + '|' + to + '|' + expires,
  'utf8',
);

const seed = new Uint8Array(32).fill(0x42);
const pair = nacl.sign.keyPair.fromSeed(seed);
const signature = nacl.sign.detached(new Uint8Array(bytes), pair.secretKey);

all.vectors.entity_transfer = {
  desc: 'Акт передачи владения сущностью (ПЛАН-(СЦ) §6, СЦ11): "tima.entity-transfer.v1|" + вид + "|" + номер сущности + "|" + номер следующего звена + "|" + от кого + "|" + кому + "|" + срок (Unix, с). Подпись Ed25519 ключом личности из seed. Посчитан node + tweetnacl (gen/entity-transfer.mjs)',
  inputs: { kind, entity_id: entityId, link, from, to, expires_unix: expires, seed_hex: Buffer.from(seed).toString('hex') },
  bytes_hex: bytes.toString('hex'),
  sha256_hex: createHash('sha256').update(bytes).digest('hex'),
  identity_pub_hex: Buffer.from(pair.publicKey).toString('hex'),
  signature_hex: Buffer.from(signature).toString('hex'),
};
writeFileSync(path, JSON.stringify(all, null, 2) + '\n');
console.log('entity_transfer', all.vectors.entity_transfer.sha256_hex);
