# Relay Worker

A small Cloudflare Worker that gives Huginn and Muninn short-lived TURN relay logins.
The Cloudflare TURN token stays inside Cloudflare, never inside the apps.

Files:

- `worker.js`: the Worker, and the `RelayLimits` object that keeps the daily counts.
- `wrangler.toml`: the Worker's name, the `LIMITS` binding and the storage setup, for `wrangler deploy`.
- `test.mjs`: a local check. Run `node test.mjs` in this folder. It should end with `20 tests passed`.

## How it gets to Cloudflare

Each release (a push to main that changes `code/VERSION`) deploys this folder to the Worker `huginn-relay`
(`https://huginn-relay.ravenor907.workers.dev`), after every check has passed. Test builds never touch it.
The deploy keeps the Worker's secrets. Without the two GitHub secrets below, the release still goes out
and only shows a warning that the Worker was not deployed.

## Setup steps

Do these in order. One action per step. Button names are from the Cloudflare dashboard at
dash.cloudflare.com and from GitHub. If a button has a slightly different name, pick the closest match.

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

### C. Get the Cloudflare account ID

12. In the left sidebar, click **Workers & Pages**.
13. Find **Account ID** on the right side of the page.
14. Click it to copy it into your note. This is your `CLOUDFLARE_ACCOUNT_ID`.

### D. Make the deploy token

15. Click the person icon at the top right, then **My Profile**.
16. Click **API Tokens**.
17. Click **Create Token**.
18. Next to **Edit Cloudflare Workers**, click **Use template**. (It includes **Workers Scripts: Edit**.)
19. Under **Account Resources**, pick your account.
20. Click **Continue to summary**, then **Create Token**.
21. Copy the token into your note. This is your `CLOUDFLARE_API_TOKEN`. It is shown only once.

### E. Add the two GitHub secrets

22. Open the repository on github.com and click **Settings**.
23. Click **Secrets and variables**, then **Actions**.
24. Click **New repository secret**. Name `CLOUDFLARE_API_TOKEN`, paste its value, click **Add secret**.
25. Click **New repository secret** again. Name `CLOUDFLARE_ACCOUNT_ID`, paste its value, click **Add secret**.
26. The next release deploys the Worker.

### F. Add the three Worker secrets

Do this once the Worker exists (after the first release with the GitHub secrets).

27. In Cloudflare, click **Workers & Pages**, then `huginn-relay`.
28. Click **Settings**.
29. Find **Variables and Secrets** and click **Add**.
30. Set **Type** to **Secret**, name `TURN_KEY_ID`, paste its value from your note.
31. Add a Secret named `TURN_KEY_API_TOKEN` with its value.
32. Add one more Secret named `APP_SECRET` with its value.
33. Click **Deploy**.

### G. Check it works

34. Open `https://huginn-relay.ravenor907.workers.dev/health` in a browser.
35. You should see `{"ok":true}`.
36. The Worker address and `APP_SECRET` go into the apps' constants files (see `shared/docs/relay.md`).

## Good to know

- Limits: each app install gets at most 30 logins a day, each internet address at most 60 a day (a home
  shares one address, so this is a few installs' worth), and everyone together at most 3000 a day.
  An IPv6 address counts by its first half (its /64), which is one home or one phone, so one home can't
  get around the cap by changing the rest. The day resets at midnight UTC. When a limit is hit the Worker
  answers 429.
- The counts live in one small storage object at Cloudflare (`RelayLimits`, bound as `LIMITS`). Each login
  is checked and counted in one step inside it, so logins at the same moment can't go over a limit.
- Addresses are never stored or logged. The per-address count is kept under a keyed hash (HMAC) of the address
  and the day. The key is the Worker's `TURN_KEY_API_TOKEN` secret, so the stored value can't be turned back
  into an address by trying them all. All counts from earlier days are deleted when a new day starts.
- If the storage object is missing or not working, the Worker refuses every request (answers 503).
  That is on purpose: the caps are what protect the TURN account.
- Free plan budget, per login: 1 Worker request, 1 storage object request, 3 storage rows written (and the
  same 3 or fewer deleted the next day). The free plan allows 100,000 Worker requests, 100,000 storage object
  requests and 100,000 rows written a day. The 3000 a day cap uses at most 3000 requests of each kind and
  about 18,000 rows, so 500 users fit with room to spare.
- Each login lasts 30 minutes. The apps ask for a fresh one for a call when less than 5 minutes are left.
- TURN data is billed by Cloudflare per GB relayed. Check **Realtime** in the dashboard for usage.
- Optional: in **Notifications** (left sidebar or account menu), add a usage or billing alert, so you hear
  about unexpected TURN traffic.
- To deploy by hand from this folder: `npx wrangler@4.156.0 deploy` (needs Node 22 or newer and a
  Cloudflare login).
