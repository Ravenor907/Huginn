# Relay Worker

A small Cloudflare Worker that gives Huginn and Muninn short-lived TURN relay logins.
The Cloudflare TURN token stays inside Cloudflare, never inside the apps.

Files:

- `worker.js`: the Worker. Paste this into the Cloudflare editor.
- `test.mjs`: a local check. Run `node test.mjs` in this folder. It should end with `11 tests passed`.

## Setup steps

Do these in order. One action per step. Button names are from the Cloudflare dashboard at
dash.cloudflare.com. If a button has a slightly different name, pick the closest match.

### A. Make the TURN key

1. Log in to dash.cloudflare.com.
2. In the left sidebar, click **Realtime**.
3. Click **TURN Server**.
4. Click **Create**.
5. Type the name `huginn-relay`.
6. Click **Create**.
7. Copy the **Turn Token ID** into a note. This is your `TURN_KEY_ID`.
8. Copy the **API Token** into the same note. This is your `TURN_KEY_API_TOKEN`. It is shown only once.

### B. Make the app secret

9. Open a terminal.
10. Run `openssl rand -hex 32`.
11. Copy the long line it prints into your note. This is your `APP_SECRET`.

### C. Make the Worker

12. In the left sidebar, click **Workers & Pages**.
13. Click **Create** (it may say **Create application**).
14. Pick **Worker** (it may say **Start with Hello World!**).
15. Type the name `huginn-relay`.
16. Click **Deploy**.
17. Click **Edit code**.
18. Select everything in the editor and delete it.
19. Paste all of `worker.js`.
20. Click **Deploy**.

### D. Add the three secrets

21. Go back to the Worker's page (click its name at the top left of the editor).
22. Click **Settings**.
23. Find **Variables and Secrets** and click **Add**.
24. Set **Type** to **Secret**, name `TURN_KEY_ID`, paste its value from your note.
25. Click **Add variable** (or **+ Add**) and add a Secret named `TURN_KEY_API_TOKEN` with its value.
26. Add one more Secret named `APP_SECRET` with its value.
27. Click **Deploy**.

### E. Make the KV store

28. In the left sidebar, click **Storage & Databases**, then **KV**.
29. Click **Create** (it may say **Create namespace** or **Create instance**).
30. Type the name `LIMITS`.
31. Click **Create**.

### F. Connect the KV store to the Worker

32. Open the Worker again (**Workers & Pages**, then `huginn-relay`).
33. Click **Bindings** (on some layouts it is under **Settings**).
34. Click **Add binding**.
35. Pick **KV namespace**.
36. Set **Variable name** to `LIMITS`.
37. Pick the `LIMITS` namespace from the list.
38. Click **Add binding** (or **Save**, then **Deploy** if asked).

### G. Check it works

39. On the Worker's page, copy its address. It looks like `https://huginn-relay.<your-name>.workers.dev`.
40. Open that address with `/health` on the end in a browser.
41. You should see `{"ok":true}`.
42. Send the address and your `APP_SECRET` so they can go into the apps' constants files (see `docs/relay.md`).

## Good to know

- Limits: each app install gets at most 30 logins a day, and everyone together at most 3000 a day.
  The day resets at midnight UTC. When a limit is hit the Worker answers 429.
- If the KV store is missing or not working, the Worker refuses every request (answers 503).
  So if step F is skipped, nothing works. That is on purpose.
- KV on the free Workers plan allows about 1000 writes a day. Each login uses 2 writes, so the free plan
  stops at about 500 logins a day (the Worker then answers 503). The paid Workers plan lifts this.
- KV counts are close, not exact. A burst from many places at once can go a little over a limit.
- TURN data is billed by Cloudflare per GB relayed. Check **Realtime** in the dashboard for usage.
- The Worker logs nothing about requests.
- To change the code later: open the Worker, click **Edit code**, paste the new `worker.js`, click **Deploy**.
  The secrets and the KV binding stay.
