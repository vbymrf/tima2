// Генератор канонических тест-векторов крипто-ядра TIMA.
// Эталонная реализация — tweetnacl-js (Kodium — её порт TweetNaCl) + noble (HKDF, ML-KEM).
// Kodium ОБЯЗАН выдавать те же байты. Вся «случайность» (nonce, seed) зафиксирована,
// поэтому вывод детерминирован и воспроизводим.
import nacl from 'tweetnacl';
import { hkdf } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha256';
import { ml_kem768 } from '@noble/post-quantum/ml-kem';
import { writeFileSync } from 'node:fs';

const hex = (u8) => Buffer.from(u8).toString('hex');
const bytes = (h) => new Uint8Array(Buffer.from(h, 'hex'));
const rep = (b, n) => { const u = new Uint8Array(n); u.fill(b); return u; };
const concat = (...a) => { const t = new Uint8Array(a.reduce((s, x) => s + x.length, 0)); let o = 0; for (const x of a) { t.set(x, o); o += x.length; } return t; };
const u32le = (n) => { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, n >>> 0, true); return b; };
const u64le = (n) => { const b = new Uint8Array(8); new DataView(b.buffer).setBigUint64(0, BigInt(n), true); return b; };
const lp = (s) => { const u = new TextEncoder().encode(s); return concat(u32le(u.length), u); };

const V = { schema: 'tima.crypto.v1', format_version: 1, generated_by: 'tweetnacl-js@1.0.3 + noble', note: 'Все входы фиксированы → вывод детерминирован. Kodium должен совпасть байт-в-байт.', vectors: {} };

// ── 1. SecretBox = Kodium.encryptSymmetric(key, data) → output = nonce(24) || box ──
{
  const key = rep(0x11, 32);
  const nonce = rep(0x22, 24);
  const msg = new TextEncoder().encode('TIMA secretbox KAT · привет');
  const box = nacl.secretbox(msg, nonce, key);
  V.vectors.secretbox = {
    desc: 'Kodium.encryptSymmetric(key,data) должен вернуть nonce||secretbox с этим nonce',
    key: hex(key), nonce: hex(nonce), plaintext_hex: hex(msg),
    kodium_output_hex: hex(concat(nonce, box)),
    box_only_hex: hex(box)
  };
}

// ── 2. Box = Kodium.encrypt(mySecret, theirPublic, data) → output = nonce(24) || box ──
{
  const ephSecret = rep(0x33, 32);                 // эфемерная пара отправителя
  const ephPublic = nacl.box.keyPair.fromSecretKey(ephSecret).publicKey;
  const recipSecret = rep(0x44, 32);               // ключ устройства получателя
  const recipPublic = nacl.box.keyPair.fromSecretKey(recipSecret).publicKey;
  const nonce = rep(0x55, 24);
  const messageKey = rep(0x66, 32);                // то, что оборачиваем (message_key)
  const box = nacl.box(messageKey, nonce, recipPublic, ephSecret);
  V.vectors.box_wrap = {
    desc: 'wrapped_key: Kodium.encrypt(eph_secret, recipient_public, message_key) = nonce||box',
    eph_secret: hex(ephSecret), eph_public: hex(ephPublic),
    recipient_secret: hex(recipSecret), recipient_public: hex(recipPublic),
    nonce: hex(nonce), message_key: hex(messageKey),
    kodium_output_hex: hex(concat(nonce, box))
  };
}

// ── 3. Ed25519 подпись. Ключ ВЫВОДИТСЯ из того же 32-байтного seed, что и Box (как в Kodium) ──
{
  const seed = rep(0x77, 32);                      // = секрет устройства (Box secretKey / seed)
  const kp = nacl.sign.keyPair.fromSeed(seed);
  const msg = new TextEncoder().encode('TIMA canonical_bytes stand-in');
  const sig = nacl.sign.detached(msg, kp.secretKey);
  V.vectors.ed25519 = {
    desc: 'signDetached(deviceKey, data): seed→keyPair.fromSeed; подпись 64 байта; verify=true',
    seed: hex(seed), public_key: hex(kp.publicKey),
    message_hex: hex(msg), signature_hex: hex(sig),
    verify: nacl.sign.detached.verify(msg, sig, kp.publicKey)
  };
}

// ── 4. HKDF-SHA256 (для escrow: hkdf(mlkem_shared)) ──
{
  const ikm = rep(0x88, 32), salt = rep(0x99, 16), info = new TextEncoder().encode('tima/escrow/v1');
  const out = hkdf(sha256, ikm, salt, info, 32);
  V.vectors.hkdf_sha256 = { desc: 'HKDF-SHA256, 32 байта', ikm: hex(ikm), salt: hex(salt), info_hex: hex(info), output_hex: hex(out) };
}

// ── 5. canonical_bytes (что подписывается) + его sha256. Layout — proto/README.md ──
{
  const encrypted_payload = bytes(V.vectors.secretbox.kodium_output_hex);
  const mlkem_ct = rep(0xa1, 1088), wrapped_message_key = rep(0xa2, 48);
  const sender_ephemeral_pub = bytes(V.vectors.box_wrap.eph_public);
  const ratchet_envelope = new Uint8Array(0);      // пусто → sha256 пустых байт
  const cb = concat(
    u32le(1),
    u64le(42),
    lp('11111111-1111-1111-1111-111111111111'),
    lp('22222222-2222-2222-2222-222222222222'),
    lp('33333333-3333-3333-3333-333333333333'),
    u32le(1),                                       // kind = CK_TEXT
    u64le(1750000000000),
    u64le(0),                                       // reply_to
    sha256(encrypted_payload),
    sha256(concat(mlkem_ct, wrapped_message_key)),
    sha256(sender_ephemeral_pub),
    sha256(ratchet_envelope)
  );
  V.vectors.canonical_bytes = {
    desc: 'Преобраз фиксированных полей Envelope в canonical_bytes; подписывается именно sha-preimage',
    inputs: { format_version: 1, message_id: 42, chat_id: '11111111-1111-1111-1111-111111111111', sender_id: '22222222-2222-2222-2222-222222222222', sender_device: '33333333-3333-3333-3333-333333333333', kind: 1, created_at_unix_ms: 1750000000000, reply_to: 0 },
    canonical_bytes_hex: hex(cb),
    sha256_hex: hex(sha256(cb))
  };
}

// ── 5b. MessageBody: замороженные protobuf-байты. ИСТОЧНИК — Kotlin-реализация (Wire),
//        зафиксировано на первом зелёном прогоне SerializerTest (2026-07-12); генератор
//        лишь переносит константу, чтобы регенерация не теряла вектор. Wire — референс для Go.
{
  V.vectors.message_body = {
    desc: 'Замороженные protobuf-байты MessageBody (Wire, Kotlin-реализация — референс для Go). zstd не фиксируется: нормативна только распаковка.',
    frozen_by: 'messenger-crypto SerializerTest, Wire 5.2.1, первый зелёный прогон 2026-07-12',
    inputs: {
      text: 'Привет, TIMA! 👋 жирный #тест',
      entities: [
        { type: 'ET_LINK(9)', offset: 8, length: 4, url: 'https://tima.app' },
        { type: 'ET_BOLD(1)', offset: 17, length: 6 },
        { type: 'ET_HASHTAG(11)', offset: 24, length: 5, attribute: 'тест' }
      ],
      media: [
        { media_id: 'aaaabbbb-cccc-dddd-eeee-ffff00001111', media_key: 'cc…(32 байта 0xcc)', mime: 'image/webp', size_bytes: 34567, width: 640, height: 480, blurhash: 'LKO2?U%2Tw=w', chunk_count: 1 }
      ],
      note: 'offset/length — UTF-16 code units: 👋 занимает 2 юнита, поэтому «жирный» начинается с 17'
    },
    protobuf_hex: '0a2fd09fd180d0b8d0b2d0b5d1822c2054494d412120f09f918b20d0b6d0b8d180d0bdd18bd0b92023d182d0b5d181d1821218080910081804221068747470733a2f2f74696d612e61707012060801101118061210080b101818053208d182d0b5d181d1821a6e0a2461616161626262622d636363632d646464642d656565652d6666666630303030313131311220cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc1a0a696d6167652f7765627020878e0228800530e003420c4c4b4f323f55253254773d774801'
  };
}

// ── 6. ML-KEM-768 escrow round-trip (keygen детерминирован из seed; ct рандомизирован RNG,
//        поэтому фиксируем НЕ ct, а корректность: decapsulate(ct, sk) === shared) ──
{
  const seed = rep(0xbb, 64);                       // ML-KEM keygen seed (noble: 64 байта)
  const { publicKey, secretKey } = ml_kem768.keygen(seed);
  const { cipherText, sharedSecret } = ml_kem768.encapsulate(publicKey);
  const shared2 = ml_kem768.decapsulate(cipherText, secretKey);
  V.vectors.mlkem768_escrow = {
    desc: 'ML-KEM-768: keygen из seed детерминирован; encapsulate рандомизирован. Инвариант: decap(ct,sk)===shared. ct=1088 байт.',
    keygen_seed: hex(seed),
    public_key_len: publicKey.length, secret_key_len: secretKey.length,
    ciphertext_len: cipherText.length,             // должно быть 1088
    shared_len: sharedSecret.length,
    roundtrip_ok: Buffer.compare(Buffer.from(sharedSecret), Buffer.from(shared2)) === 0,
    public_key_sha256: hex(sha256(publicKey))       // фиксирует детерминизм keygen(seed)
  };
}

writeFileSync(new URL('../vectors.json', import.meta.url), JSON.stringify(V, null, 2) + '\n');
console.log('OK: vectors written.');
for (const [k, v] of Object.entries(V.vectors)) {
  const flag = v.verify ?? v.roundtrip_ok;
  console.log(`  ${k}${flag !== undefined ? ' · check=' + flag : ''}`);
}
