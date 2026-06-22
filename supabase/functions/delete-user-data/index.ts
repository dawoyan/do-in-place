import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

serve(async (req) => {
    if (req.method === "OPTIONS") {
        return new Response("ok", { headers: { "Access-Control-Allow-Origin": "*" } });
    }

    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return new Response("Unauthorized", { status: 401 });

    // Verify the caller using their own JWT
    const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
        global: { headers: { Authorization: authHeader } }
    });
    const { data: { user }, error: userError } = await userClient.auth.getUser();
    if (userError || !user) return new Response("Unauthorized", { status: 401 });

    const userId = user.id;

    // All deletions run under service_role — never trust the client for userId
    const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    try {
        // 1. Collect owned task IDs (tasks this user created)
        const { data: ownedTasks } = await admin
            .from("tasks")
            .select("id")
            .eq("created_by_user_id", userId);
        const ownedTaskIds: string[] = (ownedTasks ?? []).map((t: { id: string }) => t.id);

        // 2. Collect assigned-only task IDs (created by someone else, assigned to this user)
        //    FK assigned_to_user_id is NOT NULL so we must delete these tasks too
        const { data: assignedTasks } = await admin
            .from("tasks")
            .select("id")
            .eq("assigned_to_user_id", userId)
            .neq("created_by_user_id", userId);
        const assignedOnlyTaskIds: string[] = (assignedTasks ?? []).map((t: { id: string }) => t.id);

        const allTaskIds = [...ownedTaskIds, ...assignedOnlyTaskIds];

        // 3. Delete task_events for all affected tasks (no cascade on this FK)
        if (allTaskIds.length > 0) {
            await admin.from("task_events").delete().in("task_id", allTaskIds);
        }
        // Also delete any remaining events where this user was the actor
        await admin.from("task_events").delete().eq("actor_user_id", userId);

        // 4. Remove this user's access to tasks shared with them (but not owned by them)
        await admin.from("task_shares").delete().eq("shared_with_user_id", userId);

        // 5. Delete task_shares for owned tasks (cascade would handle on task delete, but explicit is safer)
        if (ownedTaskIds.length > 0) {
            await admin.from("task_shares").delete().in("task_id", ownedTaskIds);
        }

        // 6. Delete shopping_list_items for all affected tasks (cascade on task delete, but explicit)
        if (allTaskIds.length > 0) {
            await admin.from("shopping_list_items").delete().in("task_id", allTaskIds);
        }

        // 7. Delete all affected tasks
        if (allTaskIds.length > 0) {
            await admin.from("tasks").delete().in("id", allTaskIds);
        }

        // 8. Delete contact_invites involving this user
        await admin.from("contact_invites").delete().eq("from_user_id", userId);
        await admin.from("contact_invites").delete().eq("to_user_id", userId);
        await admin.from("contact_invites").delete().eq("used_by_user_id", userId);

        // 9. Delete connection_invites created by this user
        //    (used_by_user_id has ON DELETE SET NULL, so those rows survive)
        await admin.from("connection_invites").delete().eq("created_by_user_id", userId);

        // 10. Delete user profile from public.users
        await admin.from("users").delete().eq("id", userId);

        // 11. Delete the auth user — this triggers cascades on auth.users FKs
        const { error: deleteAuthError } = await admin.auth.admin.deleteUser(userId);
        if (deleteAuthError) throw deleteAuthError;

        return Response.json({ ok: true });
    } catch (e) {
        console.error("delete-user-data error:", e);
        return new Response(JSON.stringify({ error: String(e) }), {
            status: 500,
            headers: { "Content-Type": "application/json" }
        });
    }
});
