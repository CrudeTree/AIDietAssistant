// index.js – CommonJS, Node 20 (uses built‑in fetch)
// Cloud Function name: checkin
const functions = require('@google-cloud/functions-framework');
const admin = require('firebase-admin');
const crypto = require('crypto');

// Admin SDK for verifying Firebase Auth tokens + server-side quota enforcement.
// In Cloud Functions, this uses the default service account credentials.
try {
  admin.initializeApp();
} catch (_) {
  // ignore "already exists" in hot reload / repeated init
}

function pickModel({ isVision }) {
  const fromEnv = (isVision ? process.env.OPENAI_VISION_MODEL : process.env.OPENAI_CHAT_MODEL) || '';
  return fromEnv.trim() || 'gpt-4o-mini';
}

function extractJsonObject(text) {
  let jsonString = (text || '').trim();
  const start = jsonString.indexOf('{');
  const end = jsonString.lastIndexOf('}');
  if (start !== -1 && end !== -1 && end > start) {
    jsonString = jsonString.slice(start, end + 1);
  }
  return JSON.parse(jsonString);
}

function extractJsonArray(text) {
  let jsonString = (text || '').trim();
  const start = jsonString.indexOf('[');
  const end = jsonString.lastIndexOf(']');
  if (start !== -1 && end !== -1 && end > start) {
    jsonString = jsonString.slice(start, end + 1);
  }
  return JSON.parse(jsonString);
}

function asNullableInt(x) {
  if (x === null || x === undefined) return null;
  const n = Number(x);
  if (!Number.isFinite(n)) return null;
  return Math.round(n);
}

function clampInt(n, min, max) {
  const x = Number(n);
  if (!Number.isFinite(x)) return min;
  return Math.min(max, Math.max(min, Math.round(x)));
}

function clampScore10(n) {
  // Ratings in this app are always 1–10. Be defensive if the model returns 0–100 etc.
  return clampInt(n, 1, 10);
}

function clampScore10Nullable(n) {
  if (n === null || n === undefined) return null;
  const x = Number(n);
  if (!Number.isFinite(x)) return null;
  return clampScore10(x);
}

function normalizeFoodCacheKey(s) {
  const raw = String(s || '').trim().toLowerCase();
  return raw.replace(/\s+/g, ' ').slice(0, 120);
}

function normalizeDietKey(s) {
  const v = String(s || '').trim().toUpperCase();
  return v || 'UNKNOWN';
}

function foodCacheDocId(version, normalizedKey) {
  // Use a short hash to avoid Firestore doc ID edge cases and keep it deterministic.
  const h = crypto.createHash('sha256').update(normalizedKey).digest('hex').slice(0, 16);
  return `food_v${version}_${h}`;
}

function hashUid(uid) {
  // Stable, non-PII identifier for logs.
  return crypto.createHash('sha256').update(String(uid || '')).digest('hex').slice(0, 10);
}

function logEvent(name, fields) {
  // Structured logs are easier to filter in Cloud Logging.
  console.log(JSON.stringify({ event: name, ...fields }));
}

function normalizeCategory(x) {
  const v = String(x || '').trim().toUpperCase();
  return v;
}

function computeLocalMinutes({ clientLocalMinutes, timezoneOffsetMinutes }) {
  // Prefer client-provided local minutes (best). Fallback to server time converted by offset (if provided).
  if (Number.isFinite(Number(clientLocalMinutes))) return Math.round(Number(clientLocalMinutes));

  const now = new Date();
  // serverMinutes is UTC minutes-of-day
  const serverUtcMinutes = now.getUTCHours() * 60 + now.getUTCMinutes();
  if (Number.isFinite(Number(timezoneOffsetMinutes))) {
    // JS getTimezoneOffset is "minutes behind UTC" (e.g. PST => 480). We'll accept the same convention.
    const offset = Math.round(Number(timezoneOffsetMinutes));
    const local = (serverUtcMinutes - offset) % (24 * 60);
    return local < 0 ? local + 24 * 60 : local;
  }
  // Worst-case fallback: server local clock
  return now.getHours() * 60 + now.getMinutes();
}

function computeLocalDayKey({ clientLocalDate }) {
  // Prefer client-provided local date (YYYY-MM-DD) so "daily" resets at the user's local midnight.
  const s = String(clientLocalDate || '').trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s;
  // Fallback: UTC day key (legacy behavior).
  return new Date().toISOString().slice(0, 10);
}

function minutesUntilWindowStart(nowMin, startMin) {
  const day = 24 * 60;
  const diff = (startMin - nowMin + day) % day;
  return diff;
}

function formatDuration(mins) {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  if (h <= 0) return `${m}m`;
  if (m <= 0) return `${h}h`;
  return `${h}h ${m}m`;
}

function truncateText(s, maxChars) {
  if (!s) return '';
  const str = String(s);
  if (str.length <= maxChars) return str;
  return str.slice(0, maxChars) + `… [truncated ${str.length - maxChars} chars]`;
}

functions.http('checkin', async (req, res) => {
  try {
    const {
      lastMeal,
      hungerSummary,
      weightTrend,
      minutesSinceMeal, // legacy; may be null
      tone,
      userMessage,
      mode,
      inventorySummary,
      productUrl,
      labelUrl,
      nutritionFactsUrl,
      dietType,

      // fasting / local time
      fastingPreset,
      eatingWindowStartMinutes,
      eatingWindowEndMinutes,
      clientLocalMinutes,
      clientLocalDate,
      timezoneOffsetMinutes,

      // Meal logging
      mealPhotoUrl,
      mealGrams,
      mealText,

      // Menu scan
      menuPhotoUrl,

      // Generate meal: de-dup recipes
      existingRecipeTitles,
      // Generate meal: required ingredients (must include)
      requiredIngredients,

      // Freeform chat: continuity
      chatContext,

      // Batch text analysis
      foodNames
    } = req.body || {};

    // ---------- 0) AUTH (required) ----------
    const authHeader = req.headers.authorization || req.headers.Authorization || '';
    const token = String(authHeader).startsWith('Bearer ') ? String(authHeader).slice('Bearer '.length).trim() : null;
    if (!token) {
      return res.status(401).json({ text: 'AUTH_REQUIRED' });
    }

    let decoded;
    try {
      decoded = await admin.auth().verifyIdToken(token);
    } catch (e) {
      console.error('verifyIdToken failed', e?.message || e);
      return res.status(401).json({ text: 'AUTH_INVALID' });
    }

    const uid = decoded.uid;
    const db = admin.firestore();
    const FOOD_CACHE_VERSION = 1;

    // Load user profile (for non-tier fields). Tier is verified server-side via RevenueCat below.
    const userSnap = await db.collection('users').doc(uid).get();
    const user = userSnap.exists ? (userSnap.data() || {}) : {};

    async function fetchTierFromRevenueCat(appUserId) {
      const secret = process.env.REVENUECAT_SECRET_API_KEY || process.env.REVENUECAT_API_KEY_SECRET;
      if (!secret) return null;

      // RevenueCat V1: GET /v1/subscribers/{app_user_id}
      // Secret key is from RevenueCat dashboard (NOT the public SDK key).
      const url = `https://api.revenuecat.com/v1/subscribers/${encodeURIComponent(String(appUserId))}`;
      const resp = await fetch(url, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${secret}`,
          'Content-Type': 'application/json'
        }
      });

      if (!resp.ok) {
        const text = await resp.text().catch(() => '');
        console.error('RevenueCat verify failed', resp.status, text?.slice?.(0, 220) || text);
        return null;
      }

      const data = await resp.json();
      const sub = data?.subscriber || {};
      const active = sub?.entitlements?.active || {};

      // Map entitlements expected in RevenueCat dashboard.
      //
      // Prefer explicit env-configured entitlement identifiers so different dashboards work without code changes.
      // Example env:
      // - RC_ENTITLEMENT_PRO="premium"
      // - RC_ENTITLEMENT_REGULAR="basic"
      const proEnt = String(process.env.RC_ENTITLEMENT_PRO || 'premium').trim();
      const regEnt = String(process.env.RC_ENTITLEMENT_REGULAR || 'basic').trim();
      if (proEnt && active[proEnt]) return 'PRO';
      if (regEnt && active[regEnt]) return 'REGULAR';

      // Heuristic fallback: handle common naming variations.
      const keys = Object.keys(active || {});
      const keyLower = keys.map((k) => String(k || '').toLowerCase());
      const hasPro =
        keyLower.some((k) => k.includes('premium') || k === 'pro' || k.includes('pro_') || k.includes('_pro') || k.includes('plus'));
      const hasReg =
        keyLower.some((k) => k.includes('basic') || k.includes('regular') || k.includes('standard'));
      if (hasPro) return 'PRO';
      if (hasReg) return 'REGULAR';

      // Fallback: infer from active subscriptions if entitlements aren't configured / present yet.
      // RevenueCat subscriber payload includes a "subscriptions" map keyed by product identifier.
      // We'll treat any non-expired subscription as active and map by product id keywords.
      const subs = sub?.subscriptions && typeof sub.subscriptions === 'object' ? sub.subscriptions : {};
      const nowMs = Date.now();
      const activeProductIds = [];
      for (const [productId, info] of Object.entries(subs || {})) {
        const expiresMsRaw = info?.expires_date_ms ?? info?.expires_date_ms ?? null;
        const expiresDateRaw = info?.expires_date ?? null;
        let expiresMs = null;
        if (expiresMsRaw !== null && expiresMsRaw !== undefined) {
          const n = Number(expiresMsRaw);
          if (Number.isFinite(n)) expiresMs = n;
        } else if (typeof expiresDateRaw === 'string' && expiresDateRaw.trim()) {
          const t = Date.parse(expiresDateRaw);
          if (Number.isFinite(t)) expiresMs = t;
        }
        const isActive = expiresMs == null ? false : expiresMs > nowMs;
        if (isActive) activeProductIds.push(String(productId || ''));
      }
      const prodLower = activeProductIds.map((s) => s.toLowerCase());
      const proByProduct =
        prodLower.some((p) => p.includes('premium') || p.includes('pro') || p.includes('plan_premium'));
      const regByProduct =
        prodLower.some((p) => p.includes('basic') || p.includes('regular') || p.includes('plan_basic'));
      if (proByProduct) return 'PRO';
      if (regByProduct) return 'REGULAR';

      return 'FREE';
    }

    // Server-side tier is the source of truth for quotas.
    // Fail closed to FREE if RevenueCat secret isn't configured or verification fails.
    const verifiedTier = (await fetchTierFromRevenueCat(uid)) || 'FREE';

    // Best-effort sync back to Firestore for UI consistency, but never trust it for enforcement.
    try {
      const currentTier = String(user.subscriptionTier || 'FREE').toUpperCase();
      if (currentTier !== verifiedTier) {
        await db.collection('users').doc(uid).set(
          {
            subscriptionTier: verifiedTier,
            subscriptionTierUpdatedAt: admin.firestore.FieldValue.serverTimestamp()
          },
          { merge: true }
        );
      }
    } catch (e) {
      console.error('Failed to sync subscriptionTier to Firestore', e?.message || e);
    }

    const tier = verifiedTier;

    function dailyChatLimitForTier(t) {
      // Premium is marketed as "unlimited", but enforce a fair-use cap server-side.
      if (t === 'PRO') return 200;
      if (t === 'REGULAR') return 50;
      return 25; // FREE
    }

    // Separate budget for "expensive" analysis calls (vision + food analysis).
    // These are cost drivers and should be rate-limited independently from chat.
    function dailyAnalysisLimitForTier(t) {
      // Allow env overrides without redeploying code (optional).
      const free = Number(process.env.DAILY_ANALYSIS_LIMIT_FREE || 30);
      const regular = Number(process.env.DAILY_ANALYSIS_LIMIT_REGULAR || 200);
      const pro = Number(process.env.DAILY_ANALYSIS_LIMIT_PRO || 800);
      if (t === 'PRO') return pro;
      if (t === 'REGULAR') return regular;
      return free; // FREE
    }

    async function tryConsumeAnalysisQuota(cost) {
      const now = new Date();
      const dayKey = computeLocalDayKey({ clientLocalDate });
      const limit = dailyAnalysisLimitForTier(tier);
      const usageRef = db.collection('users').doc(uid).collection('meta').doc('usageDaily').collection('days').doc(dayKey);

      const c = Number(cost);
      const inc = Number.isFinite(c) ? Math.max(1, Math.round(c)) : 1;

      try {
        await db.runTransaction(async (tx) => {
          const snap = await tx.get(usageRef);
          const used = snap.exists ? Number(snap.data()?.analysisCount || 0) : 0;
          if (used + inc > limit) {
            const err = new Error('DAILY_ANALYSIS_LIMIT_REACHED');
            err.code = 'LIMIT_ANALYSIS';
            throw err;
          }
          tx.set(
            usageRef,
            {
              analysisCount: used + inc,
              tier,
              updatedAt: admin.firestore.FieldValue.serverTimestamp()
            },
            { merge: true }
          );
        });
        return true;
      } catch (e) {
        if (e?.code === 'LIMIT_ANALYSIS') return false;
        console.error('analysis quota transaction failed', e);
        // Fail closed to protect spend if quota check infrastructure is failing.
        return false;
      }
    }

    // Count only "chat submissions" (not food photo analysis):
    // - freeform chat
    // - generate meal
    // - daily plan
    const countableModes = new Set(['FREEFORM', 'GENERATE_MEAL', 'DAILY_PLAN']);
    const modeKey = String(mode || 'freeform').toUpperCase();

    if (countableModes.has(modeKey)) {
      const dayKey = computeLocalDayKey({ clientLocalDate });
      const limit = dailyChatLimitForTier(tier);
      const usageRef = db.collection('users').doc(uid).collection('meta').doc('usageDaily').collection('days').doc(dayKey);

      try {
        await db.runTransaction(async (tx) => {
          const snap = await tx.get(usageRef);
          const used = snap.exists ? Number(snap.data()?.chatCount || 0) : 0;
          if (used >= limit) {
            const msg =
              tier === 'FREE'
                ? 'DAILY_LIMIT_REACHED_FREE'
                : tier === 'REGULAR'
                  ? 'DAILY_LIMIT_REACHED_REGULAR'
                  : 'DAILY_LIMIT_REACHED_PRO';
            const err = new Error(msg);
            err.code = 'LIMIT';
            err.used = used;
            err.limit = limit;
            err.tier = tier;
            err.dayKey = dayKey;
            throw err;
          }
          tx.set(
            usageRef,
            {
              chatCount: used + 1,
              tier,
              updatedAt: admin.firestore.FieldValue.serverTimestamp()
            },
            { merge: true }
          );
        });
      } catch (e) {
        if (e?.code === 'LIMIT') {
          return res.status(429).json({
            text: 'DAILY_LIMIT_REACHED',
            tier: String(e?.tier || tier),
            used: Number.isFinite(Number(e?.used)) ? Number(e.used) : null,
            limit: Number.isFinite(Number(e?.limit)) ? Number(e.limit) : limit,
            dayKey: String(e?.dayKey || dayKey)
          });
        }
        console.error('quota transaction failed', e);
        return res.status(500).json({ text: 'Service unavailable.' });
      }
    }

    // Safety cap: prevent runaway prompts as user data grows.
    // This is a CHAR cap (not tokens), but it keeps costs bounded.
    const MAX_CONTEXT_CHARS = Number(process.env.MAX_CONTEXT_CHARS || 30000);
    const safeInventorySummary = truncateText(inventorySummary || '', Math.floor(MAX_CONTEXT_CHARS * 0.85));
    const safeUserMessage = truncateText(userMessage || '', 2000);

    // ---------- 1) FOOD ANALYSIS MODE (vision + optional text) ----------
    if (mode === 'analyze_food') {
      const isTextOnly = !!lastMeal && !productUrl && !labelUrl && !nutritionFactsUrl;
      const uidHash = hashUid(uid);

      // Global cache (shared across users) for text-only analysis so common foods don't cost extra calls.
      // Cache is keyed ONLY by the input food string (diet-neutral),
      // so all users get the same description payload.
      const cacheKey = isTextOnly ? normalizeFoodCacheKey(lastMeal) : null;
      if (isTextOnly && cacheKey) {
        try {
          const docId = foodCacheDocId(FOOD_CACHE_VERSION, cacheKey);
          const snap = await db.collection('foodCatalog').doc(docId).get();
          if (snap.exists) {
            const data = snap.data() || {};
            if (data.version === FOOD_CACHE_VERSION && data.payload) {
              logEvent('food_cache_hit', {
                uid: uidHash,
                tier,
                mode: 'analyze_food',
                key: cacheKey,
                docId
              });
              return res.json({ text: String(data.payload) });
            }
          }
        } catch (e) {
          // Cache is best-effort; ignore failures and fall through to OpenAI.
          console.warn('food cache read failed', e?.message || e);
        }
        logEvent('food_cache_miss', { uid: uidHash, tier, mode: 'analyze_food', key: cacheKey });
      }

      // Basic input validation (prevents weird URLs from being sent to OpenAI).
      function isAllowedImageUrl(u) {
        if (!u) return false;
        const s = String(u).trim();
        if (!s.startsWith('https://')) return false;
        if (s.length > 2000) return false;
        // Only allow Firebase Storage / GCS download URLs from this project.
        return s.includes('firebasestorage.googleapis.com') || s.includes('storage.googleapis.com');
      }
      if (productUrl && !isAllowedImageUrl(productUrl)) {
        return res.status(400).json({ text: 'INVALID_PRODUCT_URL' });
      }
      if (labelUrl && !isAllowedImageUrl(labelUrl)) {
        return res.status(400).json({ text: 'INVALID_LABEL_URL' });
      }
      if (nutritionFactsUrl && !isAllowedImageUrl(nutritionFactsUrl)) {
        return res.status(400).json({ text: 'INVALID_NUTRITION_URL' });
      }
      if (!isTextOnly && !productUrl && !labelUrl && !nutritionFactsUrl) {
        return res.status(400).json({ text: 'NO_INPUTS' });
      }

      // Quota gate: only charge quota if we are about to call OpenAI.
      // - text-only cache hits returned above don't consume quota
      // - text-only cache misses DO consume quota (1)
      // - image analysis consumes quota (1)
      const willCallOpenAi = true;
      if (willCallOpenAi) {
        const ok = await tryConsumeAnalysisQuota(1);
        if (!ok) {
          logEvent('analysis_quota_denied', { uid: uidHash, tier, mode: 'analyze_food', cost: 1 });
        return res.status(429).json({ text: 'DAILY_LIMIT_REACHED', tier });
        }
        logEvent('analysis_quota_consumed', { uid: uidHash, tier, mode: 'analyze_food', cost: 1 });
      }

      const instructions = `
You are a nutrition coach.

Inputs you may receive:
- Up to 3 images:
  1) product/front photo (main display)
  2) ingredients photo (optional)
  3) nutrition facts photo (optional)
- OR text-only name/description (Food name) if no images were provided.

Diet context: do NOT tailor the wording to the user's diet. Your description must be diet-neutral.

Your job:
1) Infer what the product or meal is (a short, normalized FOOD or MEAL name).
2) General health rating (1–10):
   - "rating": general HEALTH rating for most people (1 = very unhealthy, 10 = very healthy).
3) Diet ratings (1–10) for ALL of these diets (even if user isn't on them yet):
   - NO_DIET, CARNIVORE, KETO, VEGAN, VEGETARIAN, PALEO, OMNIVORE, OTHER
   Put them in:
   - "dietRatings": { "CARNIVORE": 2, "VEGAN": 9, ... }
4) "dietFitRating":
   - Set dietFitRating = rating (diet-neutral). The app will select the correct diet-specific score from dietRatings.
5) Allergy “fit/safety” ratings (1–10) for common allergies:
   - Keys:
     PEANUT, TREE_NUT, DAIRY, EGG, SOY, WHEAT_GLUTEN, SESAME, FISH, SHELLFISH
   Scale:
   - 10 = very likely safe / no obvious ingredient risk
   - 1 = clearly contains that allergen or is very risky
   If unclear, be conservative and use mid values (4–6), not 10.
6) Nutrition + ingredient information:
   - If NUTRITION FACTS photo is present and readable: use it.
   - If INGREDIENTS photo is present and readable: use it.
   - If photos are missing/unreadable OR text-only: you must ESTIMATE typical values.
   Return:
     - estimatedCalories (number|null)
     - estimatedProteinG (number|null)
     - estimatedCarbsG (number|null)
     - estimatedFatG (number|null)
     - ingredientsText (string; "" if truly unknown)
   It's OK to return null for numeric fields if you have no clue.
7) Summary + concerns:
   - summary: 2–4 short sentences explaining what it is and why the ratings make sense.
   - concerns: one string like "high sugar; seed oils; additives" or "".

IMPORTANT about "accepted":
- accepted = TRUE for ANYTHING clearly EDIBLE food/drink/meal (even unhealthy).
- accepted = FALSE only if inputs do NOT show/describe food OR you truly can't tell what it is.
- Do NOT reject unhealthy foods; use ratings + concerns.

Return ONE JSON object ONLY with EXACT keys:

{
  "accepted": boolean,
  "rating": number,
  "dietFitRating": number,
  "dietRatings": { "NO_DIET": number, "CARNIVORE": number, "KETO": number, "VEGAN": number, "VEGETARIAN": number, "PALEO": number, "OMNIVORE": number, "OTHER": number },
  "allergyRatings": { "PEANUT": number, "TREE_NUT": number, "DAIRY": number, "EGG": number, "SOY": number, "WHEAT_GLUTEN": number, "SESAME": number, "FISH": number, "SHELLFISH": number },
  "normalizedName": string,
  "summary": string,
  "concerns": string,
  "estimatedCalories": number|null,
  "estimatedProteinG": number|null,
  "estimatedCarbsG": number|null,
  "estimatedFatG": number|null,
  "ingredientsText": string
}

No extra keys. No markdown. No text outside JSON.
      `.trim();

      const content = [];

      // Images (optional)
      if (productUrl) content.push({ type: 'image_url', image_url: { url: productUrl } });
      if (labelUrl) content.push({ type: 'image_url', image_url: { url: labelUrl } });
      if (nutritionFactsUrl) content.push({ type: 'image_url', image_url: { url: nutritionFactsUrl } });

      // Text hint (ONLY if provided by client; for text-only mode)
      if (lastMeal) content.push({ type: 'text', text: `Food name: ${lastMeal}` });

      content.push({ type: 'text', text: instructions });

      const resp = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`
        },
        body: JSON.stringify({
          model: pickModel({ isVision: true }),
          messages: [
            {
              role: 'system',
              content: 'You are a precise nutrition coach. Always respond with strict JSON when asked.'
            },
            { role: 'user', content }
          ],
          temperature: 0.2
        })
      });

      if (!resp.ok) {
        const text = await resp.text();
        console.error('OpenAI analyze_food error', resp.status, text);
        return res.status(502).json({
          text: JSON.stringify({
            accepted: false,
            rating: 0,
            dietFitRating: 0,
            dietRatings: {
              NO_DIET: 0, CARNIVORE: 0, KETO: 0, VEGAN: 0, VEGETARIAN: 0, PALEO: 0, OMNIVORE: 0, OTHER: 0
            },
            allergyRatings: {
              PEANUT: 0, TREE_NUT: 0, DAIRY: 0, EGG: 0, SOY: 0, WHEAT_GLUTEN: 0, SESAME: 0, FISH: 0, SHELLFISH: 0
            },
            normalizedName: 'unknown',
            summary: 'Service unavailable.',
            concerns: 'OpenAI error',
            estimatedCalories: null,
            estimatedProteinG: null,
            estimatedCarbsG: null,
            estimatedFatG: null,
            ingredientsText: ''
          })
        });
      }

      const data = await resp.json();
      const contentText = data.choices?.[0]?.message?.content?.trim() || '';

      let parsed;
      try {
        parsed = extractJsonObject(contentText);

        // dietFitRating is diet-neutral in this app; default it to rating.
        if (typeof parsed.dietFitRating !== 'number') parsed.dietFitRating = parsed.rating;

        // Clamp ratings (1-10) defensively in case the model returns 0-100 etc.
        parsed.rating = clampScore10(parsed.rating);
        parsed.dietFitRating = clampScore10(parsed.dietFitRating);
        if (parsed.dietRatings && typeof parsed.dietRatings === 'object') {
          for (const k of Object.keys(parsed.dietRatings)) {
            parsed.dietRatings[k] = clampScore10(parsed.dietRatings[k]);
          }
        }
        if (parsed.allergyRatings && typeof parsed.allergyRatings === 'object') {
          for (const k of Object.keys(parsed.allergyRatings)) {
            parsed.allergyRatings[k] = clampScore10(parsed.allergyRatings[k]);
          }
        }

        // Normalize nullable nutrition ints
        parsed.estimatedCalories = (parsed.estimatedCalories === null || parsed.estimatedCalories === undefined)
          ? null
          : asNullableInt(parsed.estimatedCalories);
        parsed.estimatedProteinG = (parsed.estimatedProteinG === null || parsed.estimatedProteinG === undefined)
          ? null
          : asNullableInt(parsed.estimatedProteinG);
        parsed.estimatedCarbsG = (parsed.estimatedCarbsG === null || parsed.estimatedCarbsG === undefined)
          ? null
          : asNullableInt(parsed.estimatedCarbsG);
        parsed.estimatedFatG = (parsed.estimatedFatG === null || parsed.estimatedFatG === undefined)
          ? null
          : asNullableInt(parsed.estimatedFatG);

        if (typeof parsed.ingredientsText !== 'string') parsed.ingredientsText = '';
      } catch (e) {
        console.error('Failed to parse analyze_food JSON', e, contentText);
        parsed = {
          accepted: false,
          rating: 0,
          dietFitRating: 0,
          dietRatings: {
            NO_DIET: 0, CARNIVORE: 0, KETO: 0, VEGAN: 0, VEGETARIAN: 0, PALEO: 0, OMNIVORE: 0, OTHER: 0
          },
          allergyRatings: {
            PEANUT: 0, TREE_NUT: 0, DAIRY: 0, EGG: 0, SOY: 0, WHEAT_GLUTEN: 0, SESAME: 0, FISH: 0, SHELLFISH: 0
          },
          normalizedName: 'unknown',
          summary: 'Unable to analyze food.',
          concerns: 'Invalid JSON from model',
          estimatedCalories: null,
          estimatedProteinG: null,
          estimatedCarbsG: null,
          estimatedFatG: null,
          ingredientsText: ''
        };
      }

      // Best-effort write-through cache for text-only analysis.
      if (isTextOnly && cacheKey && parsed && parsed.accepted === true) {
        try {
          const docId = foodCacheDocId(FOOD_CACHE_VERSION, cacheKey);
          await db.collection('foodCatalog').doc(docId).set(
            {
              version: FOOD_CACHE_VERSION,
              key: cacheKey,
              normalizedName: parsed.normalizedName || '',
              rating: parsed.rating,
              dietFitRating: parsed.dietFitRating,
              // Store as a string payload so we can return it verbatim and avoid re-serialization diffs.
              payload: JSON.stringify(parsed),
              updatedAt: admin.firestore.FieldValue.serverTimestamp()
            },
            { merge: true }
          );
          logEvent('food_cache_write', { uid: uidHash, tier, mode: 'analyze_food', key: cacheKey });
        } catch (e) {
          console.warn('food cache write failed', e?.message || e);
        }
      }

      return res.json({ text: JSON.stringify(parsed) });
    }

    // ---------- 1d) DAILY PLAN MODE (strict JSON) ----------
    if (String(mode || '').toLowerCase() === 'daily_plan') {
      const localMinutes = computeLocalMinutes({ clientLocalMinutes, timezoneOffsetMinutes });
      const targetCalories = asNullableInt(req.body?.dailyTargetCalories);
      const mealCountRaw = asNullableInt(req.body?.dailyMealCount);
      const mealCount = clampInt(mealCountRaw ?? 3, 1, 3);
      const savedOnly = !!req.body?.dailySavedRecipesOnly;
      // Compute eating window bounds locally (can't reference variables declared later in the file).
      const ws = Number.isFinite(Number(eatingWindowStartMinutes)) ? Math.round(Number(eatingWindowStartMinutes)) : null;
      const we = Number.isFinite(Number(eatingWindowEndMinutes)) ? Math.round(Number(eatingWindowEndMinutes)) : null;

      // Saved recipes: accept a small list of {id,title,text} (text optional).
      const savedRecipesRaw = Array.isArray(req.body?.savedRecipesForPlan) ? req.body.savedRecipesForPlan : [];
      const savedRecipes = savedRecipesRaw
        .map((r) => ({
          id: String(r?.id || '').trim(),
          title: String(r?.title || '').trim(),
          text: truncateText(String(r?.text || ''), 3500)
        }))
        .filter((r) => r.id && r.title)
        .slice(0, 12);

      const safeTarget = (targetCalories && targetCalories > 0) ? targetCalories : null;

      const instructions = `
You are a diet assistant inside a diet-management app.

Goal: Create a simple DAILY MEAL PLAN for today using ${mealCount} meal(s).
Daily calorie target: ${safeTarget ?? "unspecified"} (if unspecified, pick a reasonable default based on context and keep it moderate).

Diet type: ${dietType || "unknown"}.
User local minutes now: ${localMinutes}.
Fasting preset: ${fastingPreset || "NONE"}.
Eating window minutes: ${ws ?? "none"}-${we ?? "none"}.

User’s known foods/inventory summary:
${safeInventorySummary || "unknown"}

Saved recipes only: ${savedOnly}.

Rules:
- Output STRICT JSON only. No markdown, no extra text.
- Return calories as integers (estimatedCalories).
- If dailyTargetCalories is provided: aim to be close (within about ±10% is fine). Do not obsess over exactness.
- If saved recipes only is true: choose from the provided saved recipes. Do NOT invent new recipes.
- If saved recipes only is false: you MAY generate new recipes.
- Each meal should include a full cookable recipe text in the app’s format:
  Title: ...
  Why it fits: ...
  Ingredients (from my list):
  - ...
  Steps:
  1) ...
  Cook time: ...
  Temp: ...
  Notes / swaps (only using my list): ...

Return JSON object with EXACT shape:
{
  "dailyTargetCalories": number|null,
  "totalEstimatedCalories": number,
  "meals": [
    {
      "id": string,
      "title": string,
      "estimatedCalories": number,
      "recipeText": string,
      "sourceRecipeId": string|null
    }
  ]
}
      `.trim();

      const savedBlock = savedOnly
        ? `\nSaved recipes (choose from these IDs):\n${savedRecipes.map(r => `- ${r.id}: ${r.title}`).join('\n') || '(none)'}\n`
        : '';
      const savedTextsBlock = savedOnly
        ? `\nSaved recipe texts (may help you estimate calories):\n${savedRecipes.map(r => `\n---\nID: ${r.id}\nTitle: ${r.title}\nText:\n${r.text || ''}`).join('\n')}\n`
        : '';

      const prompt = [
        instructions,
        savedBlock,
        savedTextsBlock
      ].join('\n');

      const resp = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`
        },
        body: JSON.stringify({
          model: pickModel({ isVision: false }),
          messages: [
            { role: 'system', content: 'You are a precise assistant. Always respond with strict JSON when asked.' },
            { role: 'user', content: prompt }
          ],
          temperature: 0.5
        })
      });

      if (!resp.ok) {
        const text = await resp.text();
        console.error('OpenAI daily_plan error', resp.status, text);
        return res.status(502).json({ text: 'Service unavailable.' });
      }

      const data = await resp.json();
      const contentText = data.choices?.[0]?.message?.content?.trim() || '';
      let parsed;
      try {
        parsed = extractJsonObject(contentText);
      } catch (e) {
        console.error('Failed to parse daily_plan JSON', e, contentText);
        return res.status(502).json({ text: 'Invalid JSON from model' });
      }
      return res.json({ text: JSON.stringify(parsed) });
    }

    // ---------- 1a) BATCH FOOD NAME ANALYSIS MODE (text only) ----------
    if (mode === 'analyze_food_batch') {
      const names = Array.isArray(foodNames) ? foodNames.map((x) => String(x || '').trim()).filter(Boolean) : [];
      if (names.length === 0) {
        return res.status(400).json({ text: 'NO_FOOD_NAMES' });
      }
      const uidHash = hashUid(uid);
      // Safety cap to keep token usage bounded. Client can chunk requests.
      const MAX_BATCH = Number(process.env.ANALYZE_FOOD_BATCH_MAX || 25);
      if (names.length > MAX_BATCH) {
        return res.status(400).json({ text: 'TOO_MANY_FOOD_NAMES' });
      }

      // Try cache for each name first; only send misses to OpenAI.
      const cacheKeys = names.map(normalizeFoodCacheKey);
      const docIds = cacheKeys.map((k) => foodCacheDocId(FOOD_CACHE_VERSION, k));
      const docRefs = docIds.map((id) => db.collection('foodCatalog').doc(id));

      const cachedPayloads = await Promise.all(
        docRefs.map(async (ref) => {
          try {
            const snap = await ref.get();
            if (!snap.exists) return null;
            const d = snap.data() || {};
            if (d.version !== FOOD_CACHE_VERSION || !d.payload) return null;
            return String(d.payload);
          } catch (_) {
            return null;
          }
        })
      );

      const results = new Array(names.length).fill(null);
      const misses = [];
      const missIndexes = [];

      for (let i = 0; i < names.length; i++) {
        const payload = cachedPayloads[i];
        if (payload) {
          try {
            results[i] = JSON.parse(payload);
          } catch (_) {
            // Treat invalid cache as miss
            results[i] = null;
          }
        }
        if (!results[i]) {
          misses.push(names[i]);
          missIndexes.push(i);
        }
      }

      // If everything was cached, return immediately.
      if (misses.length === 0) {
        logEvent('food_batch_cache_all_hit', {
          uid: uidHash,
          tier,
          mode: 'analyze_food_batch',
          total: names.length,
          hits: names.length,
          misses: 0
        });
        return res.json({ text: JSON.stringify(results) });
      }

      // Quota gate: cost equals the number of misses (the number of items we'll ask OpenAI to analyze).
      const ok = await tryConsumeAnalysisQuota(misses.length);
      if (!ok) {
        logEvent('analysis_quota_denied', {
          uid: uidHash,
          tier,
          mode: 'analyze_food_batch',
          cost: misses.length,
          total: names.length,
          hits: names.length - misses.length,
          misses: misses.length
        });
        return res.status(429).json({ text: 'DAILY_LIMIT_REACHED', tier });
      }
      logEvent('analysis_quota_consumed', {
        uid: uidHash,
        tier,
        mode: 'analyze_food_batch',
        cost: misses.length,
        total: names.length,
        hits: names.length - misses.length,
        misses: misses.length
      });

      const instructions = `
You are a nutrition coach.

Input: a list of food/meal/ingredient names (text only). Diet context: ${dietType || "unknown"}.

For EACH input name, return an object with EXACT keys:
{
  "accepted": boolean,
  "rating": number,
  "dietFitRating": number,
  "dietRatings": { "NO_DIET": number, "CARNIVORE": number, "KETO": number, "VEGAN": number, "VEGETARIAN": number, "PALEO": number, "OMNIVORE": number, "OTHER": number },
  "allergyRatings": { "PEANUT": number, "TREE_NUT": number, "DAIRY": number, "EGG": number, "SOY": number, "WHEAT_GLUTEN": number, "SESAME": number, "FISH": number, "SHELLFISH": number },
  "normalizedName": string,
  "summary": string,
  "concerns": string,
  "estimatedCalories": number|null,
  "estimatedProteinG": number|null,
  "estimatedCarbsG": number|null,
  "estimatedFatG": number|null,
  "ingredientsText": string
}

Rules:
- Keep the ORDER exactly the same as the input list.
- accepted=true for anything that is plausibly edible food/drink/meal/ingredient.
- accepted=false only if clearly not food.
- dietFitRating MUST equal dietRatings[${(dietType || '').toUpperCase() || 'NO_DIET'}] when that key exists; otherwise set dietFitRating=rating.
- Output ONE JSON ARRAY ONLY. No markdown. No extra text.
      `.trim();

      const fullPrompt = [
        `Food names (in order):`,
        ...misses.map((n, i) => `${i + 1}) ${n}`),
        '',
        instructions
      ].join('\n');

      const resp = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`
        },
        body: JSON.stringify({
          model: pickModel({ isVision: false }),
          messages: [
            {
              role: 'system',
              content: 'You are a precise nutrition coach. Always respond with strict JSON when asked.'
            },
            { role: 'user', content: fullPrompt }
          ],
          temperature: 0.2
        })
      });

      if (!resp.ok) {
        const text = await resp.text();
        console.error('OpenAI analyze_food_batch error', resp.status, text);
        return res.status(502).json({ text: 'Service unavailable.' });
      }

      const data = await resp.json();
      const contentText = data.choices?.[0]?.message?.content?.trim() || '';

      let parsed;
      try {
        parsed = extractJsonArray(contentText);
        if (!Array.isArray(parsed)) throw new Error('Expected array');

        // Normalize nutrition ints + ensure ingredientsText string + clamp ratings 1-10
        parsed = parsed.map((x) => {
          const o = x || {};
          o.estimatedCalories = (o.estimatedCalories === null || o.estimatedCalories === undefined)
            ? null
            : asNullableInt(o.estimatedCalories);
          o.estimatedProteinG = (o.estimatedProteinG === null || o.estimatedProteinG === undefined)
            ? null
            : asNullableInt(o.estimatedProteinG);
          o.estimatedCarbsG = (o.estimatedCarbsG === null || o.estimatedCarbsG === undefined)
            ? null
            : asNullableInt(o.estimatedCarbsG);
          o.estimatedFatG = (o.estimatedFatG === null || o.estimatedFatG === undefined)
            ? null
            : asNullableInt(o.estimatedFatG);
          if (typeof o.ingredientsText !== 'string') o.ingredientsText = '';
          if (typeof o.rating === 'number') o.rating = clampScore10(o.rating);
          if (typeof o.dietFitRating === 'number') o.dietFitRating = clampScore10(o.dietFitRating);
          if (o.dietRatings && typeof o.dietRatings === 'object') {
            for (const k of Object.keys(o.dietRatings)) {
              o.dietRatings[k] = clampScore10(o.dietRatings[k]);
            }
          }
          if (o.allergyRatings && typeof o.allergyRatings === 'object') {
            for (const k of Object.keys(o.allergyRatings)) {
              o.allergyRatings[k] = clampScore10(o.allergyRatings[k]);
            }
          }
          return o;
        });
      } catch (e) {
        console.error('Failed to parse analyze_food_batch JSON', e, contentText);
        return res.status(502).json({ text: 'Invalid JSON from model' });
      }

      // Fill misses back into the original order and write-through cache (best-effort).
      for (let j = 0; j < parsed.length; j++) {
        const idx = missIndexes[j];
        results[idx] = parsed[j];
      }

      // Cache accepted results only (don't store nonsense like "rock").
      await Promise.all(
        parsed.map(async (o, j) => {
          const idx = missIndexes[j];
          const key = cacheKeys[idx];
          if (!o || o.accepted !== true) return;
          try {
            const docId = foodCacheDocId(FOOD_CACHE_VERSION, key);
            await db.collection('foodCatalog').doc(docId).set(
              {
                version: FOOD_CACHE_VERSION,
                key,
                normalizedName: o.normalizedName || '',
                rating: o.rating,
                dietFitRating: o.dietFitRating,
                payload: JSON.stringify(o),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
              },
              { merge: true }
            );
          } catch (_) {
            // ignore cache write errors
          }
        })
      );

      logEvent('food_batch_cache_write', {
        uid: uidHash,
        tier,
        mode: 'analyze_food_batch',
        cachedCount: parsed.filter((o) => o && o.accepted === true).length,
        total: names.length
      });

      return res.json({ text: JSON.stringify(results) });
    }

    // ---------- 1b) MEAL ANALYSIS MODE (meal photo + grams/text) ----------
    if (mode === 'menu_scan') {
      const uidHash = hashUid(uid);
      // Basic input validation: only allow Firebase/GCS URLs.
      if (!menuPhotoUrl || typeof menuPhotoUrl !== 'string' || !String(menuPhotoUrl).startsWith('https://')) {
        return res.status(400).json({ text: 'NO_MENU_PHOTO' });
      }
      const murl = String(menuPhotoUrl).trim();
      if (murl.length > 2000 || !(murl.includes('firebasestorage.googleapis.com') || murl.includes('storage.googleapis.com'))) {
        return res.status(400).json({ text: 'INVALID_MENU_URL' });
      }

      const ok = await tryConsumeAnalysisQuota(1);
      if (!ok) {
        logEvent('analysis_quota_denied', { uid: uidHash, tier, mode: 'menu_scan', cost: 1 });
        return res.status(429).json({ text: 'DAILY_LIMIT_REACHED', tier });
      }
      logEvent('analysis_quota_consumed', { uid: uidHash, tier, mode: 'menu_scan', cost: 1 });

      const instructions = `
You are a nutrition coach helping a user order at a restaurant.

You will be given a PHOTO of a restaurant MENU.
Diet context for the current user setting: ${dietType || "unknown"}.

Known foods/inventory summary (optional; may be unrelated to restaurant): ${safeInventorySummary || "unknown"}.

Your job:
1) Read the menu items as best you can from the photo.
2) Recommend the TOP 3 best options for the user's diet.
3) For each option:
   - Give the exact menu item name (or closest match if text is unclear)
   - Give 1–2 sentence reasoning tied to the diet
   - Give 1–3 simple modifications (e.g., "no bun", "swap fries for salad", "sauce on side")
4) Also list 3 "Avoid" items that are likely worst for the diet, with a short reason.
5) If the photo is too blurry/unreadable, ask the user to retake the photo and give tips (closer, better lighting, no glare).

Return plain text only. No JSON. Keep it concise and actionable.
      `.trim();

      const content = [];
      if (menuPhotoUrl) content.push({ type: 'image_url', image_url: { url: menuPhotoUrl } });
      content.push({ type: 'text', text: instructions });

      const resp = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`
        },
        body: JSON.stringify({
          model: pickModel({ isVision: true }),
          messages: [
            { role: 'system', content: 'You are a helpful nutrition coach. Be practical and decisive.' },
            { role: 'user', content }
          ],
          temperature: 0.4
        })
      });

      if (!resp.ok) {
        const text = await resp.text();
        console.error('OpenAI menu_scan error', resp.status, text);
        return res.status(502).json({ text: 'Service unavailable.' });
      }

      const data = await resp.json();
      const text = data.choices?.[0]?.message?.content?.trim() || 'Service unavailable.';
      return res.json({ text });
    }

    // ---------- 1b) MEAL ANALYSIS MODE (meal photo + grams/text) ----------
    if (mode === 'analyze_meal') {
      const uidHash = hashUid(uid);
      // Only count quota if we're about to call OpenAI.
      const ok = await tryConsumeAnalysisQuota(1);
      if (!ok) {
        logEvent('analysis_quota_denied', { uid: uidHash, tier, mode: 'analyze_meal', cost: 1 });
        return res.status(429).json({ text: 'DAILY_LIMIT_REACHED', tier });
      }
      logEvent('analysis_quota_consumed', { uid: uidHash, tier, mode: 'analyze_meal', cost: 1 });

      const instructions = `
You are a nutrition coach helping estimate meal size.

You may be given:
- A photo of a prepared meal, and/or
- A meal name/description (mealText), and/or
- Total grams weight (mealGrams).

Diet context: ${dietType || "unknown"}.
Known foods/inventory summary: ${safeInventorySummary || "unknown"}.

Your job:
1) If the inputs look like a real meal/food, set accepted=true. If clearly NOT food, accepted=false.
2) Infer a short normalized meal name (normalizedMealName).
3) Estimate total calories for this meal (estimatedCalories).
4) If possible, estimate macros in grams: estimatedProteinG, estimatedCarbsG, estimatedFatG.
   If unsure, you may return null for macros.
5) Add short notes (1–2 sentences) in "notes" describing what you saw and your confidence.

Return ONE JSON object ONLY with EXACT keys:

{
  "accepted": boolean,
  "normalizedMealName": string,
  "estimatedCalories": number,
  "estimatedProteinG": number|null,
  "estimatedCarbsG": number|null,
  "estimatedFatG": number|null,
  "notes": string
}

No extra keys. No markdown. No text outside JSON.
      `.trim();

      const content = [];
      if (mealPhotoUrl) content.push({ type: 'image_url', image_url: { url: mealPhotoUrl } });
      if (mealText) content.push({ type: 'text', text: `Meal text: ${mealText}` });
      if (mealGrams !== undefined && mealGrams !== null) content.push({ type: 'text', text: `Meal grams: ${mealGrams}` });
      content.push({ type: 'text', text: instructions });

      const resp = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`
        },
        body: JSON.stringify({
          model: pickModel({ isVision: true }),
          messages: [
            { role: 'system', content: 'You are a precise nutrition coach. Always respond with strict JSON when asked.' },
            { role: 'user', content }
          ],
          temperature: 0.2
        })
      });

      if (!resp.ok) {
        const text = await resp.text();
        console.error('OpenAI analyze_meal error', resp.status, text);
        return res.status(502).json({
          text: JSON.stringify({
            accepted: false,
            normalizedMealName: 'unknown',
            estimatedCalories: 0,
            estimatedProteinG: null,
            estimatedCarbsG: null,
            estimatedFatG: null,
            notes: 'Service unavailable.'
          })
        });
      }

      const data = await resp.json();
      const contentText = data.choices?.[0]?.message?.content?.trim() || '';

      let parsed;
      try {
        parsed = extractJsonObject(contentText);
      } catch (e) {
        console.error('Failed to parse analyze_meal JSON', e, contentText);
        parsed = {
          accepted: false,
          normalizedMealName: 'unknown',
          estimatedCalories: 0,
          estimatedProteinG: null,
          estimatedCarbsG: null,
          estimatedFatG: null,
          notes: 'Invalid JSON from model'
        };
      }

      return res.json({ text: JSON.stringify(parsed) });
    }

    // ---------- 1c) GENERATE MEAL MODE (recipe text) ----------
    if (mode === 'generate_meal') {
      const localMinutes = computeLocalMinutes({ clientLocalMinutes, timezoneOffsetMinutes });
      const windowStart = Number.isFinite(Number(eatingWindowStartMinutes)) ? Math.round(Number(eatingWindowStartMinutes)) : null;
      const windowEnd = Number.isFinite(Number(eatingWindowEndMinutes)) ? Math.round(Number(eatingWindowEndMinutes)) : null;
      const preset = normalizeCategory(fastingPreset) || 'NONE';

      const existingTitles = Array.isArray(existingRecipeTitles)
        ? existingRecipeTitles
            .map((t) => String(t || '').trim())
            .filter(Boolean)
            .slice(0, 30)
        : [];

      const required = Array.isArray(requiredIngredients)
        ? requiredIngredients
            .map((t) => String(t || '').trim())
            .filter(Boolean)
            .slice(0, 6)
        : [];

      const instructions = `
You are a diet assistant. The user wants a MEAL recipe they can cook.

Diet type: ${dietType || "unknown"}.
User local time (minutes from midnight): ${localMinutes}.
Fasting preset: ${preset}.
Eating window (minutes from midnight): ${windowStart ?? "none"}–${windowEnd ?? "none"}.

User’s categorized items summary (Meals/Ingredients/Snacks):
${safeInventorySummary || "unknown"}

Previously saved recipe titles (avoid duplicates or near-duplicates):
${existingTitles.length ? existingTitles.map(t => `- ${t}`).join('\n') : "(none)"}

Required ingredients (MUST be included in the recipe):
${required.length ? required.map(t => `- ${t}`).join('\n') : "(none)"}

Rules:
- Build meals primarily from INGREDIENTS. You MAY use SNACKS if they can reasonably be used in cooking.
- Do NOT assume ingredients not listed.
- Do not use emojis or emoji icons anywhere in the response.
- If required ingredients are provided, you MUST include ALL of them in:
  - the Ingredients list, and
  - the Steps (used in a real way, not as a garnish unless appropriate).
- If a required ingredient is not available in the user's list, call it out under "Missing / recommended to buy".
- If ingredients are too sparse to cook (e.g. only salt), say so and provide a small shopping list.
- If the user is outside their eating window and they ask "can I eat", you may say "not yet" and how long until the window opens.
- Avoid generating a recipe that matches or closely resembles any previously saved recipe title.
- If your first idea is too similar, pick a different cuisine, protein, primary carb, and cooking method.

Return plain text (not JSON) with this format:

Title: <meal name>
Why it fits: <1-2 sentences referencing diet>
Ingredients (from my list):
- ...
Missing / recommended to buy (if needed):
- ...
Steps:
1) ...
2) ...
3) ...
Cook time: <minutes>
Temp: <if applicable>
Notes / swaps (only using my list): <short>
      `.trim();

      // IMPORTANT:
      // For text-only chat models, some model IDs reject "messages[].content" as an array of {type:"text"} blocks.
      // Use a plain string prompt here to avoid 400s (which surface as HTTP 502 from this function).
      const fullPrompt = [
        instructions,
        safeUserMessage ? `\n\nUser request: ${safeUserMessage}` : ''
      ].join('');

      const resp = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`
        },
        body: JSON.stringify({
          model: pickModel({ isVision: false }),
          messages: [
            {
              role: 'system',
              content: 'You are a helpful cooking assistant and nutrition coach. Follow the requested format exactly.'
            },
            {
              role: 'user',
              content: fullPrompt
            }
          ],
          temperature: 0.6
        })
      });

      if (!resp.ok) {
        const text = await resp.text();
        console.error('OpenAI generate_meal error', resp.status, text);
        return res.status(502).json({ text: 'Service unavailable.' });
      }

      const data = await resp.json();
      const text = data.choices?.[0]?.message?.content?.trim() || 'Service unavailable.';
      return res.json({ text });
    }

    // ---------- 2) CHAT / COACH MODES ----------
    const localMinutes = computeLocalMinutes({ clientLocalMinutes, timezoneOffsetMinutes });
    const dayKey = computeLocalDayKey({ clientLocalDate });
    const windowStart = Number.isFinite(Number(eatingWindowStartMinutes)) ? Math.round(Number(eatingWindowStartMinutes)) : null;
    const windowEnd = Number.isFinite(Number(eatingWindowEndMinutes)) ? Math.round(Number(eatingWindowEndMinutes)) : null;
    const hasWindow = windowStart != null && windowEnd != null;
    const insideWindow = hasWindow ? (windowStart <= windowEnd
      ? (localMinutes >= windowStart && localMinutes < windowEnd)
      : (localMinutes >= windowStart || localMinutes < windowEnd)) : true;
    const minsToOpen = hasWindow && !insideWindow ? minutesUntilWindowStart(localMinutes, windowStart) : 0;

    // Calorie tracking (stored per local dayKey by the client after parsing <APP_ACTION>).
    let caloriesTodayTotal = 0;
    let caloriesTodayEntryCount = 0;
    try {
      const calDoc = await db
        .collection('users')
        .doc(uid)
        .collection('calories')
        .doc(dayKey)
        .get();
      if (calDoc.exists) {
        const d = calDoc.data() || {};
        caloriesTodayTotal = Number.isFinite(Number(d.totalCalories)) ? Math.round(Number(d.totalCalories)) : 0;
        caloriesTodayEntryCount = Number.isFinite(Number(d.entryCount)) ? Math.round(Number(d.entryCount)) : 0;
      }
    } catch (e) {
      console.warn('caloriesToday read failed', e?.message || e);
    }

    const contextLines = [
      "You are a short, casual, varied diet assistant inside a diet-management app.",
      `Diet type: ${dietType || "unknown"}.`,
      `Fasting preset: ${fastingPreset || "NONE"}.`,
      `Eating window minutes: ${hasWindow ? `${windowStart}-${windowEnd}` : "none"}.`,
      `User local minutes now: ${localMinutes}.`,
      `Local dayKey: ${dayKey}.`,
      `Calories logged today so far: ${caloriesTodayTotal} (entries: ${caloriesTodayEntryCount}).`,
      `Inside eating window: ${insideWindow}.`,
      `Minutes until window opens: ${hasWindow ? minsToOpen : "n/a"}.`,
      `Last meal (legacy): ${lastMeal || "unknown"}.`,
      `Recent hunger + signals: ${hungerSummary || "unknown"}.`,
      `Weight trend delta: ${weightTrend ?? "unknown"}.`,
      `Minutes since meal (legacy): ${minutesSinceMeal ?? "unknown"}.`,
      `Known foods/inventory: ${safeInventorySummary || "unknown"}.`,
      `Mode: ${mode || "freeform"}.`,
      `Tone hints: ${tone || "short, casual, varied, human, not templated"}.`
    ];

    // If the user message is NOT about food/diet/the app, route to a "normal assistant" prompt
    // (no diet-app rules, no app-action behaviors).
    const isFoodRelated = (() => {
      const s = String(safeUserMessage || '').toLowerCase().trim();
      if (!s) return false;
      const re =
        /\b(food|foods|meal|meals|recipe|recipes|cook|cooking|ingredient|ingredients|snack|snacks|breakfast|lunch|dinner|calorie|calories|protein|carb|carbs|fat|macros|macro|fast|fasting|eat|eating|hungry|hunger|diet|keto|vegan|carnivore|grocery|groceries|menu|menus|restaurant|nutrition|healthy|weight|fasted)\b/;
      return re.test(s);
    })();

    const behavior = `
You are a helpful, conversational assistant inside a diet-management app.

Core scope:
- Help the user manage their Foods list, get quick food opinions, and chat about diet/fasting/menus.
- The app CAN estimate and track daily calories when the user tells you what they ate.
- Eating window is informational: explain it; don’t command.

Conversation:
- If the message isn’t about food/diet/this app, just answer normally (don’t force diet talk).
- If you answer a non-food question, do NOT append a “food-related questions…” redirect. Just answer the question.
- Keep continuity; interpret “it/that/this” as the most recent food/topic unless unclear.

Daily calories:
- If the user is telling you what they ate/drank today, estimate calories and update their daily total.
- Use "Calories logged today so far" from context when answering "How many calories so far today?".
- When (and only when) the user is logging what they ate, end your message with EXACTLY:
  <APP_ACTION>{"type":"LOG_CALORIES","entries":[{"label":"2 eggs and toast","calories":380}]}</APP_ACTION>
- Return calories as integers; be reasonable and conservative with estimates. If unsure, say it’s an estimate.

Recipes:
- If the user asks for a recipe/meal to cook, output it in this exact structure so it can be saved:
  Title: ...
  Why it fits: ...
  Ingredients (from my list):
  - ...
  Steps:
  1) ...
- If (and only if) you are providing a cookable recipe/meal, append this tag at the VERY END:
  <APP_KIND>RECIPE</APP_KIND>

App actions:
- Only claim an item was added/saved if you included a valid action block.
- Do NOT include <APP_ACTION> blocks when generating a recipe unless the user explicitly asked to add something to their list.
- To add foods, end your message with EXACTLY:
  <APP_ACTION>{"type":"ADD_FOODS","items":[{"name":"broccoli","category":"INGREDIENT"}]}</APP_ACTION>
- Users CAN add foods not already in their list. Default category to INGREDIENT if unspecified.
- JSON must be valid. No extra text inside the tag.
`.trim();

    const prompt = isFoodRelated
      ? [
          contextLines.join(" "),
          "",
          chatContext ? `Recent conversation (most recent last):\n${truncateText(chatContext, 2000)}` : "Recent conversation: (none)",
          "",
          `User message: ${safeUserMessage || "none"}.`,
          "",
          behavior
        ].join("\n")
      : [
          // Keep this prompt minimal so the assistant behaves like "normal ChatGPT".
          "Answer the user's question directly and normally.",
          "Do not steer the conversation back to diet/food unless the user asks.",
          // App safety: avoid accidental action tags on non-food answers.
          "Do NOT output <APP_ACTION> or <APP_KIND> tags in this response.",
          "",
          chatContext ? `Recent conversation (most recent last):\n${truncateText(chatContext, 1200)}` : "Recent conversation: (none)",
          "",
          `User message: ${safeUserMessage || "none"}.`
        ].join("\n");

    const chatResp = await fetch('https://api.openai.com/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`
      },
      body: JSON.stringify({
        model: pickModel({ isVision: false }),
        messages: [
          {
            role: 'system',
            content: isFoodRelated
              ? 'You are a helpful, conversational assistant. When the user talks about food/hunger/diet/cooking/fasting window or this app, also follow any extra food-coach behavior described in the user message. Otherwise, just answer normally.'
              : 'You are a helpful, conversational assistant. Answer normally.'
          },
          { role: 'user', content: prompt }
        ],
        temperature: 0.8
      })
    });

    if (!chatResp.ok) {
      const text = await chatResp.text();
      console.error('OpenAI chat error', chatResp.status, text);
      return res.status(502).json({ text: 'Service unavailable.' });
    }

    const chatData = await chatResp.json();
    const text = chatData.choices?.[0]?.message?.content?.trim() || 'Service unavailable.';
    res.json({ text });
  } catch (err) {
    console.error(err);
    res.status(500).json({ text: 'Service unavailable.' });
  }
});


