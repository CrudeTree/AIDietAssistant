// index.js – CommonJS, Node 20 (uses built‑in fetch)
// Cloud Function name: checkin
const functions = require('@google-cloud/functions-framework');
const admin = require('firebase-admin');

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

function asNullableInt(x) {
  if (x === null || x === undefined) return null;
  const n = Number(x);
  if (!Number.isFinite(n)) return null;
  return Math.round(n);
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
      timezoneOffsetMinutes,

      // Meal logging
      mealPhotoUrl,
      mealGrams,
      mealText
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

    // Load user profile to determine tier limits (server-side source of truth).
    const userSnap = await db.collection('users').doc(uid).get();
    const user = userSnap.exists ? (userSnap.data() || {}) : {};
    const tier = String(user.subscriptionTier || 'FREE').toUpperCase();

    function dailyChatLimitForTier(t) {
      if (t === 'PRO') return 150;
      if (t === 'REGULAR') return 50;
      return 5; // FREE
    }

    // Count only "chat submissions" (not food photo analysis):
    // - freeform chat
    // - generate meal
    const countableModes = new Set(['FREEFORM', 'GENERATE_MEAL']);
    const modeKey = String(mode || 'freeform').toUpperCase();

    if (countableModes.has(modeKey)) {
      const now = new Date();
      const dayKey = now.toISOString().slice(0, 10); // YYYY-MM-DD (UTC). OK for MVP; later use client local date if needed.
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
          return res.status(429).json({ text: 'DAILY_LIMIT_REACHED' });
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
      const instructions = `
You are a nutrition coach.

Inputs you may receive:
- Up to 3 images:
  1) product/front photo (main display)
  2) ingredients photo (optional)
  3) nutrition facts photo (optional)
- OR text-only name/description (Food name) if no images were provided.

Diet context for the *current user setting*: ${dietType || "unknown"}.

Your job:
1) Infer what the product or meal is (a short, normalized FOOD or MEAL name).
2) General health rating (1–10):
   - "rating": general HEALTH rating for most people (1 = very unhealthy, 10 = very healthy).
3) Diet ratings (1–10) for ALL of these diets (even if user isn't on them yet):
   - NO_DIET, CARNIVORE, KETO, VEGAN, VEGETARIAN, PALEO, OMNIVORE, OTHER
   Put them in:
   - "dietRatings": { "CARNIVORE": 2, "VEGAN": 9, ... }
4) "dietFitRating":
   - MUST match the rating for the current dietType if that dietType is one of the keys above.
   - If dietType is unknown, set dietFitRating = rating.
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

        // Ensure dietFitRating exists and matches the selected diet if possible
        if (typeof parsed.dietFitRating !== 'number') {
          const key = (dietType || '').toUpperCase();
          parsed.dietFitRating =
            (parsed.dietRatings && typeof parsed.dietRatings[key] === 'number')
              ? parsed.dietRatings[key]
              : parsed.rating;
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

      return res.json({ text: JSON.stringify(parsed) });
    }

    // ---------- 1b) MEAL ANALYSIS MODE (meal photo + grams/text) ----------
    if (mode === 'analyze_meal') {
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

      const instructions = `
You are an AI Food Coach. The user wants a MEAL recipe they can cook.

Diet type: ${dietType || "unknown"}.
User local time (minutes from midnight): ${localMinutes}.
Fasting preset: ${preset}.
Eating window (minutes from midnight): ${windowStart ?? "none"}–${windowEnd ?? "none"}.

User’s categorized items summary (Meals/Ingredients/Snacks):
${safeInventorySummary || "unknown"}

Rules:
- Build meals primarily from INGREDIENTS. You MAY use SNACKS if they can reasonably be used in cooking.
- Do NOT assume ingredients not listed.
- If ingredients are too sparse to cook (e.g. only salt), say so and provide a small shopping list.
- If the user is outside their eating window and they ask "can I eat", you may say "not yet" and how long until the window opens.

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
    const windowStart = Number.isFinite(Number(eatingWindowStartMinutes)) ? Math.round(Number(eatingWindowStartMinutes)) : null;
    const windowEnd = Number.isFinite(Number(eatingWindowEndMinutes)) ? Math.round(Number(eatingWindowEndMinutes)) : null;
    const hasWindow = windowStart != null && windowEnd != null;
    const insideWindow = hasWindow ? (windowStart <= windowEnd
      ? (localMinutes >= windowStart && localMinutes < windowEnd)
      : (localMinutes >= windowStart || localMinutes < windowEnd)) : true;
    const minsToOpen = hasWindow && !insideWindow ? minutesUntilWindowStart(localMinutes, windowStart) : 0;

    const contextLines = [
      "You are a short, casual, varied assistant and nutrition coach.",
      `Diet type: ${dietType || "unknown"}.`,
      `Fasting preset: ${fastingPreset || "NONE"}.`,
      `Eating window minutes: ${hasWindow ? `${windowStart}-${windowEnd}` : "none"}.`,
      `User local minutes now: ${localMinutes}.`,
      `Inside eating window: ${insideWindow}.`,
      `Minutes until window opens: ${hasWindow ? minsToOpen : "n/a"}.`,
      `Last meal (legacy): ${lastMeal || "unknown"}.`,
      `Recent hunger + signals: ${hungerSummary || "unknown"}.`,
      `Weight trend delta: ${weightTrend ?? "unknown"}.`,
      `Minutes since meal (legacy): ${minutesSinceMeal ?? "unknown"}.`,
      `Known foods/inventory: ${safeInventorySummary || "unknown"}.`,
      `User message: ${safeUserMessage || "none"}.`,
      `Mode: ${mode || "freeform"}.`,
      `Tone hints: ${tone || "short, casual, varied, human, not templated"}.`
    ];

    const behavior = `
You are a general assistant AND a strict but caring nutrition coach.

VERY IMPORTANT:
- If the user's message is NOT about food, hunger, diet, weight, meals, cooking, fasting window, or this app, IGNORE all food-coach rules below and DO NOT mention eating at all. Just answer like a normal assistant.
- If the user IS talking about food, hunger, diet, weight, when to eat, what to eat, cooking, cravings, fasting window, or this app, then you enter FOOD COACH MODE.

FOOD COACH MODE:
- The user does NOT decide what to eat; you decide based on diet + lists + timing window.
- Never ask "Do you want to eat now?" — either say not yet, or tell them what to eat/do.

Timing rules:
- If the user has an eating window and we are OUTSIDE it:
  - Do NOT tell them to eat.
  - If they ask "can I eat", answer: "not yet" + how long until the window opens (use the provided Minutes until window opens).
- If we are INSIDE the window:
  - If they are asking for cooking ideas, use INGREDIENTS to propose a meal (step-based if asked).
  - If they ask for something to eat, pick from Meals/Snacks list; prefer higher health + diet-fit.

Inventory rules:
- Use categorized lists: Meals, Ingredients, Snacks.
- If asked "cook for dinner", choose a meal recipe primarily from INGREDIENTS. If too sparse, say what’s missing and suggest a small shopping list.

Response length:
- For mode "freeform": keep it short (1–3 sentences).
- For mode "generate_meal": handled separately above.
`.trim();

    const prompt = contextLines.join(" ") + " " + behavior;

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
            content:
              'You are a helpful, conversational assistant. When the user talks about food/hunger/diet/cooking/fasting window or this app, also follow any extra food-coach behavior described in the user message. Otherwise, just answer normally.'
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


