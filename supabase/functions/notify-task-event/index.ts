import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const FIREBASE_SERVICE_ACCOUNT = Deno.env.get("FIREBASE_SERVICE_ACCOUNT")!;

async function getFcmAccessToken(serviceAccount: Record<string, string>): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    const header = { alg: "RS256", typ: "JWT" };
    const payload = {
        iss: serviceAccount.client_email,
        sub: serviceAccount.client_email,
        aud: "https://oauth2.googleapis.com/token",
        iat: now,
        exp: now + 3600,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
    };

    const encode = (obj: unknown) =>
        btoa(JSON.stringify(obj)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

    const signingInput = `${encode(header)}.${encode(payload)}`;

    const pemKey = serviceAccount.private_key
        .replace(/-----BEGIN PRIVATE KEY-----/, "")
        .replace(/-----END PRIVATE KEY-----/, "")
        .replace(/\n/g, "");

    const keyData = Uint8Array.from(atob(pemKey), (c) => c.charCodeAt(0));
    const cryptoKey = await crypto.subtle.importKey(
        "pkcs8", keyData,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false, ["sign"]
    );

    const signature = await crypto.subtle.sign(
        "RSASSA-PKCS1-v1_5",
        cryptoKey,
        new TextEncoder().encode(signingInput)
    );

    const jwt = `${signingInput}.${btoa(String.fromCharCode(...new Uint8Array(signature)))
        .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`;

    const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`,
    });

    const tokenData = await tokenRes.json();
    return tokenData.access_token;
}

serve(async (req) => {
    if (req.method !== "POST") {
        return json({ error: "method_not_allowed" }, 405);
    }

    const { event_type, task_id, task_title, actor_user_id, actor_name, creator_user_id } =
        await req.json();

    if (actor_user_id === creator_user_id) {
        return json({ skipped: "self" });
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    const { data: creator, error } = await supabase
        .from("users")
        .select("fcm_token, notify_on_task_cancelled")
        .eq("id", creator_user_id)
        .single();

    if (error || !creator) return json({ skipped: "creator_not_found" }, 404);

    const shouldNotify = creator.notify_on_task_cancelled !== false;
    const fcmToken: string | null = creator.fcm_token ?? null;

    if (!shouldNotify) return json({ skipped: "preference_disabled" });
    if (!fcmToken)     return json({ skipped: "no_fcm_token" });

    const serviceAccount = JSON.parse(FIREBASE_SERVICE_ACCOUNT);
    const accessToken = await getFcmAccessToken(serviceAccount);
    const projectId = serviceAccount.project_id;

    const fcmType = event_type === "REJECTED" ? "task_rejected" : "task_cancelled";
    const verbPast = event_type === "REJECTED" ? "rejected" : "cancelled";

    const fcmPayload = {
        message: {
            token: fcmToken,
            data: {
                type: fcmType,
                taskId: task_id,
                taskTitle: task_title,
                byName: actor_name,
            },
            notification: {
                title: event_type === "REJECTED" ? "Task rejected" : "Task cancelled",
                body: `${actor_name} ${verbPast}: ${task_title}`,
            },
            android: { priority: "high" },
        },
    };

    const fcmRes = await fetch(
        `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
        {
            method: "POST",
            headers: {
                Authorization: `Bearer ${accessToken}`,
                "Content-Type": "application/json",
            },
            body: JSON.stringify(fcmPayload),
        }
    );

    if (!fcmRes.ok) {
        const detail = await fcmRes.text();
        return json({ error: "fcm_send_failed", detail }, 500);
    }

    return json({ sent: true, fcmType });
});

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}
