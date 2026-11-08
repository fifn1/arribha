// عرّبها intake: receives English game lines that have no professional translation yet.
// Isolated (own Worker + own D1 database) and capped per day so it can never use up the account's free limits.
const DAILY_CAP = 40000;          // requests per day this Worker accepts (account free limit is 100k for all Workers)
const MAX_LINES = 60;
const PER_IP_CAP = 100;           // sends per household per day (a normal evening of play is 10-20)

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      if (req.method === "POST" && url.pathname === "/submit") return await submit(req, env);
      if (url.pathname === "/privacy") return privacy();
      if (url.pathname === "/link") return req.method === "POST" ? await linkSave(req, env) : linkPage(url);
      if (url.pathname === "/link-poll") return await linkPoll(url, env);
      if (url.pathname === "/pending" || url.pathname === "/done") {
        if (req.headers.get("authorization") !== `Bearer ${env.TOKEN}`) return new Response("no", { status: 401 });
        return url.pathname === "/pending" ? await pending(url, env) : await done(req, env);
      }
      if (url.pathname === "/translate") {
        if (req.headers.get("authorization") !== `Bearer ${env.TOKEN}`) return new Response("no", { status: 401 });
        return await translate(req, env);
      }
      return new Response("arribha", { status: 200 });
    } catch (e) {
      return new Response("error", { status: 500 });
    }
  },
};

async function submit(req, env) {
  const day = new Date().toISOString().slice(0, 10);
  const row = await env.DB.prepare("SELECT n FROM daily WHERE day = ?").bind(day).first();
  if (row && row.n >= DAILY_CAP) return new Response("full", { status: 429 });
  // per household (internet address) per day: one noisy sender cannot use up the day for everyone
  const ip = req.headers.get("cf-connecting-ip") || "?";
  const qk = day + ":" + ip;
  const q = await env.DB.prepare("SELECT n FROM quota WHERE k = ?").bind(qk).first();
  if (q && q.n >= PER_IP_CAP) return new Response("slow down", { status: 429 });
  await env.DB.prepare("INSERT INTO quota (k, n) VALUES (?, 1) ON CONFLICT(k) DO UPDATE SET n = n + 1").bind(qk).run();
  if (Math.random() < 0.01) await env.DB.prepare("DELETE FROM quota WHERE k < ?").bind(day).run();
  await env.DB.prepare("INSERT INTO daily (day, n) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET n = n + 1").bind(day).run();

  const body = await req.json();
  const game = String(body.game || "").slice(0, 80);
  const lines = Array.isArray(body.lines) ? body.lines.slice(0, MAX_LINES) : [];
  const now = Date.now();
  const stmts = [];
  let prev = null;
  for (const l of lines) {
    const en = String(l.en || "").slice(0, 300);
    if (en.length < 2) continue;
    const h = await fingerprint(en);   // never trust the sender's fingerprint: it would let anyone overwrite a real line
    if (l.h && l.h !== h) continue;
    // already translated for everyone: never store or translate it again
    stmts.push(env.DB.prepare("INSERT OR IGNORE INTO lines (h, en, prev, game, created) SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM known WHERE h = ?)").bind(h, en, prev, game, now, h));
    prev = en;
  }
  if (stmts.length) await env.DB.batch(stmts);
  return new Response("ok");
}

async function pending(url, env) {
  const limit = Math.min(2000, Number(url.searchParams.get("limit") || 500));
  const { results } = await env.DB.prepare("SELECT h, en, prev, game FROM lines ORDER BY game, created LIMIT ?").bind(limit).all();
  return Response.json(results);
}

// English text is deleted as soon as it is translated: only fingerprints + Arabic are kept (published).
async function done(req, env) {
  const { hashes } = await req.json();
  const stmts = [];
  for (const h of (hashes || []).slice(0, 2000)) {
    stmts.push(env.DB.prepare("DELETE FROM lines WHERE h = ?").bind(h));
    stmts.push(env.DB.prepare("INSERT OR IGNORE INTO known (h) VALUES (?)").bind(h));   // remember: done
  }
  if (stmts.length) await env.DB.batch(stmts);
  return new Response("ok");
}

// Professional translation with Cloudflare's free AI, capped per day so it never uses up the account's free AI quota.
const AI_DAILY_CAP = 120;   // batches of up to 40 lines per day
const MODEL = "@cf/openai/gpt-oss-120b";
const SYSTEM = "You are a senior Arabic video-game localizer. Translate English game text into clear, natural Modern Standard Arabic (فصحى سلسة) exactly like an official Arabic localization: keep meaning, tone and character voice, never word-for-word. Keep tokens like ZZ0, ZZ1 exactly as they are (button icons). Keep character and place names consistent and naturally transliterated. Lines of the same game come in order; use them as context. The English was read from the screen by OCR and may contain reading slips (e.g. ona = on a, wre = were): translate the intended text. Answer ONLY with a JSON object mapping each id to its Arabic text.";

const REVIEW = "You are a strict Arabic proofreader and senior game-localization editor. For each id you receive the English line and a draft Arabic translation. Check meaning against the English and the game context, fix mistranslations, wrong word choices, grammar, spelling, gender agreement and literal or unnatural phrasing, so it reads like an official Arabic localization in clear Modern Standard Arabic (فصحى سلسة). Keep tokens like ZZ0 exactly. Keep names consistent and naturally transliterated. If the English is an unfinished fragment, keep the Arabic an equally natural fragment. Use only Arabic script letters. Answer ONLY with a JSON object mapping each id to the final Arabic text.";

function textOf(out) {
  if (out?.response !== undefined) return out.response;
  if (out?.choices) return out.choices[0]?.message?.content;
  if (Array.isArray(out?.output)) {
    for (const o of out.output) for (const c of (o.content || [])) if (c.type === "output_text" && c.text) return c.text;
  }
  return out?.output_text ?? "";
}

function asJson(v) {
  if (v && typeof v === "object") return v;
  const t = String(v ?? ""); const a = t.indexOf("{"), b = t.lastIndexOf("}");
  try { return JSON.parse(t.slice(a, b + 1)); } catch (e) { return {}; }
}

async function translate(req, env) {
  const day = "ai-" + new Date().toISOString().slice(0, 10);
  const row = await env.DB.prepare("SELECT n FROM daily WHERE day = ?").bind(day).first();
  if (row && row.n >= AI_DAILY_CAP) return new Response("{}", { status: 429 });
  await env.DB.prepare("INSERT INTO daily (day, n) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET n = n + 1").bind(day).run();
  const { items, game, model, debug } = await req.json();
  const MODEL_USED = model || MODEL;
  // names already chosen for this game, so a character or place is always written the same way
  const g = String(game || "");
  const { results: known } = await env.DB.prepare("SELECT en, ar FROM names WHERE game = ? LIMIT 200").bind(g).all();
  const glossary = known.length ? " Use exactly these Arabic names: " + JSON.stringify(Object.fromEntries(known.map(r => [r.en, r.ar]))) : "";
  const out = await env.AI.run(MODEL_USED, {
    messages: [{ role: "system", content: SYSTEM + glossary }, { role: "user", content: `Game: ${game || "unknown"}
` + JSON.stringify(items) }],
    max_tokens: 9000, temperature: 0.2, reasoning: { effort: "medium" },
  });
  const draft = asJson(textOf(out));
  // second pass: strict proofreading of meaning, context, grammar and style against the English
  const pairs = {};
  for (const [id, en] of Object.entries(items)) if (draft[id]) pairs[id] = { en, ar: draft[id] };
  let final = draft;
  if (Object.keys(pairs).length) {
    try {
      const rev = await env.AI.run(MODEL_USED, {
        messages: [{ role: "system", content: REVIEW + glossary }, { role: "user", content: `Game: ${game || "unknown"}
` + JSON.stringify(pairs) }],
        max_tokens: 9000, temperature: 0.1, reasoning: { effort: "medium" },
      });
      const r = asJson(textOf(rev));
      final = { ...draft };
      for (const id of Object.keys(pairs)) if (typeof r[id] === "string" && r[id].trim()) final[id] = r[id];
    } catch (e) {}
  }
  // clean-up: Arabic letters only (no Persian/Urdu forms), keep ZZ tokens
  for (const id of Object.keys(final)) {
    if (typeof final[id] !== "string") { delete final[id]; continue; }
    final[id] = final[id].replace(/ی/g, "ي").replace(/ک/g, "ك").replace(/ە/g, "ه").trim();
  }
  // learn new names from this batch (capitalised words inside sentences) for next time
  try {
    const cand = new Set();
    for (const en of Object.values(items)) for (const m of String(en).matchAll(/(?<=[a-z,] )([A-Z][a-z]{2,}(?: [A-Z][a-z]{2,})*)/g)) cand.add(m[1]);
    const newNames = [...cand].filter(n => !known.some(k => k.en === n)).slice(0, 20);
    if (newNames.length && g) {
      const nr = await env.AI.run(MODEL_USED, { messages: [
        { role: "system", content: "For each English proper name from a video game, give the natural Arabic transliteration used in official Arabic game localization. If it is not a name (a normal word), answer null. Answer ONLY a JSON object name->Arabic or null." },
        { role: "user", content: `Game: ${g}
` + JSON.stringify(newNames) }], max_tokens: 3000, temperature: 0, reasoning: { effort: "low" } });
      const nm = asJson(textOf(nr));
      const st = [];
      for (const n of newNames) if (typeof nm[n] === "string" && nm[n].trim()) st.push(env.DB.prepare("INSERT OR IGNORE INTO names (game, en, ar) VALUES (?, ?, ?)").bind(g, n, nm[n].trim()));
      if (st.length) await env.DB.batch(st);
      if (debug) final._names = { cand: newNames, got: nm };
    }
  } catch (e) { if (debug) final._err = String(e); }
  return Response.json(final);
}

async function fingerprint(en) {
  const key = en.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const d = new Uint8Array(await crypto.subtle.digest("SHA-1", new TextEncoder().encode(key)));
  return [...d.slice(0, 6)].map(b => b.toString(16).padStart(2, "0")).join("");
}

// ---- phone sign-in by QR: the TV shows a QR with a random session id; the phone signs in to Sony,
// pastes the final address here; the TV picks up the one-time code (deleted at once, expires in 10 minutes).
const SONY = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn&response_type=code&client_id=ba495a24-818c-472b-b12d-ff231c1b5745&redirect_uri=https://remoteplay.dl.playstation.net/remoteplay/redirect&scope=psn:clientapp&request_locale=ar&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&";

function linkPage(url) {
  const sid = (url.searchParams.get("s") || "").replace(/[^a-f0-9]/g, "").slice(0, 32);
  const html = `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>عرّبها - ربط الحساب</title><style>
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif;background:linear-gradient(160deg,#101b36,#05070d);color:#e8eefc;min-height:100vh;display:flex;align-items:center;justify-content:center}
.c{width:min(92vw,440px);padding:28px 22px;text-align:center}h1{font-size:40px;margin:0 0 4px}p{color:#9fb0d0;line-height:1.7;margin:10px 0}
.b{display:block;width:100%;padding:16px;border:0;border-radius:999px;font-size:18px;font-weight:700;margin:14px 0;color:#fff;background:linear-gradient(90deg,#00a6ff,#2f6bff);text-decoration:none;box-sizing:border-box}
.g{background:rgba(255,255,255,.12)}.n{display:inline-block;width:28px;height:28px;border-radius:50%;background:#2f6bff;line-height:28px;margin-left:6px}
input{width:100%;box-sizing:border-box;padding:14px;border-radius:14px;border:1px solid rgba(255,255,255,.2);background:rgba(255,255,255,.06);color:#fff;font-size:15px;direction:ltr}
.ok{color:#34d378;font-size:20px;font-weight:700}</style></head><body><div class="c">
<h1>عرّبها</h1><p>ربط حسابك في PlayStation</p>
<p><span class="n">1</span>سجّل دخولك في PlayStation</p><a class="b" href="${SONY}" target="_blank" rel="noopener">تسجيل الدخول</a>
<p><span class="n">2</span>بعد الدخول تظهر صفحة فاضية: انسخ رابطها من شريط العنوان، وارجع هنا</p>
<button class="b g" id="p">لصق الرابط</button><input id="u" placeholder="https://remoteplay.dl.playstation.net/...">
<button class="b" id="s">إرسال للتلفزيون</button><p id="m"></p></div><script>
const u=document.getElementById('u'),m=document.getElementById('m');
document.getElementById('p').onclick=async()=>{try{u.value=await navigator.clipboard.readText()}catch(e){u.focus()}};
document.getElementById('s').onclick=async()=>{const r=await fetch('/link',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({s:'${sid}',url:u.value})});
m.innerHTML=r.ok?'<span class="ok">✓ تم. ارجع للتلفزيون</span>':'الرابط غير صحيح، تأكد إنك نسخته بعد تسجيل الدخول';};
</script></body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

async function linkSave(req, env) {
  const { s, url } = await req.json();
  const sid = String(s || "").replace(/[^a-f0-9]/g, "");
  let code = null;
  try { const u = new URL(String(url).trim()); if (u.hostname === "remoteplay.dl.playstation.net") code = u.searchParams.get("code"); } catch (e) {}
  if (sid.length !== 32 || !code || code.length > 64) return new Response("bad", { status: 400 });
  await env.DB.prepare("INSERT OR REPLACE INTO links (s, code, created) VALUES (?, ?, ?)").bind(sid, code, Date.now()).run();
  return new Response("ok");
}

async function linkPoll(url, env) {
  const sid = (url.searchParams.get("s") || "").replace(/[^a-f0-9]/g, "");
  await env.DB.prepare("DELETE FROM links WHERE created < ?").bind(Date.now() - 600000).run();
  const row = await env.DB.prepare("SELECT code FROM links WHERE s = ?").bind(sid).first();
  if (!row) return Response.json({});
  await env.DB.prepare("DELETE FROM links WHERE s = ?").bind(sid).run();
  return Response.json({ code: row.code });
}

function privacy() {
  const html = `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>عرّبها - سياسة الخصوصية</title><style>body{font-family:system-ui,Tahoma,sans-serif;max-width:720px;margin:auto;padding:24px;line-height:1.9;color:#1b2333;background:#fff}h1{font-size:28px}h2{font-size:19px;margin-top:28px}.en{direction:ltr;text-align:left;color:#444;border-top:1px solid #ddd;margin-top:40px;padding-top:16px}</style></head><body>
<h1>سياسة الخصوصية لتطبيق عرّبها</h1>
<p>عرّبها تطبيق مجاني يعرض ألعاب البلايستيشن على التلفزيون ويترجم نصوصها الإنجليزية إلى العربية.</p>
<h2>ما الذي نجمعه؟</h2><p>نجمع فقط <b>نصوص الألعاب الإنجليزية</b> التي ليس لها ترجمة احترافية بعد، لنترجمها مرة واحدة لكل اللاعبين. لا نجمع اسمك ولا بريدك ولا صورًا ولا صوتًا ولا أي معلومات شخصية.</p>
<h2>ماذا نفعل بها؟</h2><p>تُترجم آليًا بذكاء اصطناعي، ثم <b>يُحذف النص الإنجليزي</b> ويُنشر فقط «بصمة» الجملة مع ترجمتها العربية.</p>
<h2>تسجيل الدخول في PlayStation</h2><p>كلمة المرور تُكتب في صفحة Sony الرسمية وتذهب إلى Sony مباشرة، ولا تمر بخوادمنا. عند التسجيل من الجوال يمر بخادمنا رمز دخول مؤقت يُستخدم مرة واحدة ويُحذف فورًا (وينتهي خلال 10 دقائق).</p>
<h2>البث</h2><p>صورة اللعبة تنتقل مباشرة من جهاز البلايستيشن إلى التلفزيون داخل شبكة منزلك، ولا تمر بأي خادم.</p>
<h2>عنوان الإنترنت</h2><p>نستخدم عنوان الإنترنت لحظيًا لتحديد عدد الإرسالات اليومية ومنع الإزعاج، ويُحذف يوميًا.</p>
<div class="en"><h2>Privacy (English)</h2><p>Arribha only collects English game text that has no professional Arabic translation yet. It is machine-translated, then the English is deleted; only a fingerprint and the Arabic are published. No personal data is collected. PlayStation passwords go to Sony only. Gameplay video stays inside your home network. IP addresses are used only for daily rate limits and are deleted daily.</p></div>
</body></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8" } });
}
