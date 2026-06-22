import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

const VALID_TAGS = ["HEALTHIER", "LESS_HEALTHY", "NEUTRAL"]
const VALID_SUBCATEGORIES = [
  "DAIRY", "EGG", "FRUIT", "VEGETABLE", "GRAIN", "PROTEIN",
  "LEGUME", "NUT", "WATER", "TEA", "OIL", "HONEY",
  "SALTY_SNACK", "SWEET", "SUGARY_DRINK", "PROCESSED_MEAT",
  "FAST_FOOD", "REFINED", "OTHER"
]

serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 })
  }

  let body: { normalized_name?: string; language?: string }
  try {
    body = await req.json()
  } catch {
    return json({ error: "invalid JSON body" }, 400)
  }

  const normalizedName = (body.normalized_name ?? "").trim()
  const language = (body.language ?? "en").trim()

  if (!normalizedName) {
    return json({ error: "normalized_name required" }, 400)
  }

  const anthropicKey = Deno.env.get("ANTHROPIC_API_KEY")
  if (!anthropicKey) {
    return json({ error: "AI service not configured" }, 503)
  }

  const prompt = `You are a food health classifier for a shopping list app. Classify this food item.

Food item: "${normalizedName}" (language hint: ${language})

Reply with JSON only — no markdown, no explanation:
{
  "health_tag": "HEALTHIER" or "LESS_HEALTHY" or "NEUTRAL",
  "subcategory": one of [DAIRY, EGG, FRUIT, VEGETABLE, GRAIN, PROTEIN, LEGUME, NUT, WATER, TEA, OIL, HONEY, SALTY_SNACK, SWEET, SUGARY_DRINK, PROCESSED_MEAT, FAST_FOOD, REFINED, OTHER],
  "suggestion": "one short gentle tip if LESS_HEALTHY, else null"
}

Rules:
- HEALTHIER = natural whole foods (vegetables, fruits, eggs, dairy, fish, chicken, water, legumes, nuts, whole grains)
- LESS_HEALTHY = processed/sugary/fried foods (chips, candy, cola, sausage, fast food, instant noodles, cakes, energy drinks)
- NEUTRAL = borderline items (bread, cheese, butter, coffee, pasta, meat, juice, sauce)
- Keep suggestion under 10 words. No medical claims.`

  try {
    const resp = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": anthropicKey,
        "anthropic-version": "2023-06-01"
      },
      body: JSON.stringify({
        model: "claude-haiku-4-5-20251001",
        max_tokens: 200,
        messages: [{ role: "user", content: prompt }]
      })
    })

    if (!resp.ok) {
      const err = await resp.text()
      console.error("Anthropic error:", resp.status, err.slice(0, 200))
      return json({ error: "AI service error" }, 502)
    }

    const data = await resp.json()
    const text = data?.content?.[0]?.text?.trim() ?? ""
    if (!text) return json({ error: "empty AI response" }, 502)

    // Strip optional markdown fences
    const cleaned = text.replace(/^```json\s*/i, "").replace(/\s*```$/, "").trim()
    const parsed = JSON.parse(cleaned)

    const tag = VALID_TAGS.includes(parsed.health_tag) ? parsed.health_tag : null
    if (!tag) return json({ error: "invalid health_tag from AI" }, 502)

    const sub = VALID_SUBCATEGORIES.includes(parsed.subcategory) ? parsed.subcategory : "OTHER"
    const sug = typeof parsed.suggestion === "string" && parsed.suggestion.trim()
      ? parsed.suggestion.trim()
      : null

    return json({ health_tag: tag, subcategory: sub, suggestion: sug })
  } catch (e) {
    console.error("food-health-lookup error:", e)
    return json({ error: String(e) }, 500)
  }
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  })
}
