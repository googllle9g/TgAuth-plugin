# TgAuth

Telegram-based login for Paper/Purpur servers. Players authenticate through a Telegram bot
instead of a password — a code in chat for first-time linking, and inline Confirm/Reject
buttons for every login after that. Optional FastLogin integration adds real Mojang-verified
premium detection, with a safe fallback when it's not installed.

## Features

- No password login — players link their Minecraft account to Telegram once, then confirm
  logins with a button tap.
- Unauthenticated players can't move, chat, break/place blocks, open inventory, or attack/be
  attacked. Every relevant Bukkit event is covered, including edge cases like melee damage,
  buckets, and vehicles.
- Optional FastLogin integration: licensed players skip the confirm step entirely once linked;
  cracked/offline accounts always confirm through Telegram. Works standalone without FastLogin.
- Cracked ⇄ premium account migration (inventory, advancements, stats, OP) — on by default.
- Rate-limited link codes, parameterized SQL, no plaintext secrets in logs.
- Fully translatable (`lang/*.yml`), config and language files auto-update on plugin updates.

## Requirements

- Paper or a Paper fork (Purpur, etc.), Minecraft 1.21.11
- Java 21+
- A Telegram bot token from [@BotFather](https://t.me/BotFather)
- [FastLogin](https://www.spigotmc.org/resources/fastlogin.14153/) (optional, for hybrid
  premium+cracked servers)
- [LuckPerms](https://luckperms.net/) (optional, for offline admin-panel permission checks)

## Building

```bash
mvn clean package
```

Output: `target/TgAuth.jar`

## Setup

1. Drop `TgAuth.jar` into `plugins/`, start the server once to generate `config.yml`.
2. Set `telegram.bot-token` and `telegram.bot-username` in `plugins/TgAuth/config.yml`.
3. `/tgauth reload` or restart.

Player flow: join → get a code in chat → send `/link <code>` to the bot → done. On future
logins, either instant (licensed + FastLogin confirmed) or a Confirm/Reject button in Telegram.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/tgcode` | Show your current link code | — |
| `/tgauth reload` | Reload config and language files | `tgauth.admin` |
| `/tgauth unlink <player>` | Unlink a player from Telegram | `tgauth.admin` |
| `/tgauth forcelink <player> <telegramId>` | Force-link an account | `tgauth.admin` |
| `/tgauth userinfo <player>` | Link status, Telegram ID, @username, premium status | `tgauth.admin` |
| `/tgauth fastlogin` | FastLogin integration diagnostics | `tgauth.admin` |
| `/tgauth pinreset <player\|telegramId>` | Remove an admin's bot-panel PIN (they set a new one on their next `/admin`) | `tgauth.admin` |
| `/tgauth update` | Check GitHub for a newer release right now | `tgauth.admin` |
| `/tgauth migrate` | Import the old SQLite file into the active MySQL/MariaDB database (safe to repeat) | `tgauth.admin` |

## Permissions

| Node | Default | Effect |
|---|---|---|
| `tgauth.admin` | op | Admin commands |
| `tgauth.bypass` | false | Skips authorization entirely |

## Configuration reference

`config.yml` (defaults shown):

```yaml
language: "en"

telegram:
  bot-token: ""
  bot-username: ""

auth:
  code-expire-seconds: 300
  confirm-timeout-seconds: 90
  auth-timeout-seconds: 120
  reminder-interval-seconds: 20
  apply-blindness: true
  apply-slowness: true
  migrate-link-by-username: true
  migration-overwrite-existing-data: true
  cracked-ip-cooldown-seconds: 0

fastlogin:
  enabled: true
  premium-skip-confirmation: true
  premium-check-wait-seconds: 4

storage:
  type: "sqlite"                      # sqlite | mysql | mariadb
  file: "database.db"                 # the SQLite file (also the source for /tgauth migrate)
  mysql:                              # used when type is mysql or mariadb
    host: "localhost"
    port: 3306
    database: "tgauth"
    username: "tgauth"
    password: ""
    use-ssl: false
    table-prefix: "tgauth_"
    pool:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout-ms: 10000
      max-lifetime-ms: 1800000
    properties: {}                    # extra JDBC options, e.g. allowPublicKeyRetrieval: "true"

admin-pin:
  enabled: true
  freeze-admins-until-pin-set: true   # in-game admins stay frozen until they set a PIN
  setup-timeout-seconds: 300          # kick a frozen admin after this long (0 = never)
  min-length: 4                       # digits only, never below 4
  max-length: 12
  max-attempts: 3                     # wrong PINs in a row before entry is locked
  lockout-seconds: 300                # length of the FIRST lockout
  lockout-growth: 2.0                 # each further lockout in a row is this many times longer
  lockout-max-seconds: 86400          # cap for the escalated lockout
  lockout-level-reset-minutes: 1440   # quiet time after which escalation starts over
  session-timeout-minutes: 15         # idle time before the panel locks again
  siren-seconds: 10                   # 0 = no sound

update-checker:
  enabled: true
  check-interval-hours: 12
  notify-admins: true

security:
  link-max-attempts: 5
  link-attempt-window-seconds: 60
  link-lockout-seconds: 300
  global-link-max-attempts: 20
  global-link-attempt-window-seconds: 60
  global-link-lockout-seconds: 120
```

Storage is SQLite accessed through a HikariCP connection pool (max 4 connections) in WAL mode,
so next to `database.db` you will also see `database.db-wal` and `database.db-shm` while the
server runs. When backing up while the server is running, copy all three files (or stop the
server first) — copying only `database.db` can miss recent writes.

Language files live in `plugins/TgAuth/lang/`. Add a new `xx.yml` with the same keys and set
`language: xx` to add a translation.

## Trusted-IP cooldown for cracked accounts

Set `auth.cracked-ip-cooldown-seconds` above 0 to let a cracked/offline account skip the
Confirm/Reject request on reconnect, as long as it's from the same IP that confirmed recently.
Off (`0`) by default — every login always requires confirming.

Trust is scoped tightly to avoid it turning into a standing whitelist:
- Reconnecting from a **different** IP still requires confirming as normal, and immediately
  revokes the old trusted IP — it won't silently come back into play later.
- If that same IP is later used to log into a **different** account, the original account's
  trust is revoked too — a shared IP (NAT, VPN exit, family members) isn't uniquely tied to
  either player anymore.

A first-time `/link` also counts as a confirmed action and starts the trust immediately.

## Bot admin panel

Send `/admin` to the bot to open an inline-button panel (set `telegram.admin-panel-enabled:
false` in `config.yml` to disable this entirely). Two ways to get access:
- Your Telegram ID is listed in `telegram.admin-ids` in `config.yml`, **or**
- Your Telegram is linked (`/link`) to a Minecraft account that has the `tgauth.admin`
  permission. Checked live if you're online; if offline, checked via LuckPerms if it's
  installed, otherwise falls back to OP status.

### PIN protection

The panel is locked behind a PIN (`admin-pin.enabled`, on by default), and only works in a
private chat with the bot.

- **First `/admin`** — the bot asks you to set a PIN (digits only, 4–12 by default) and to repeat
  it. The other admins are notified that a PIN was set. **Do this right after installing**: until
  a PIN exists, whoever controls an admin's Telegram account can set it.
- **Admins are frozen in-game until they have a PIN** (`freeze-admins-until-pin-set`). An
  in-game admin (permission `tgauth.admin`, Telegram linked) who passes the normal Telegram login
  but has no panel PIN yet stays frozen exactly like an unauthenticated player — no moving,
  chatting, commands, or interacting — with a reminder in chat. The moment they finish setting the
  PIN in the bot they are released. If they don't, they are kicked after `setup-timeout-seconds`
  (set it to 0 to keep them frozen instead). Players with `tgauth.bypass`, and admins who exist
  only in `telegram.admin-ids` (no Minecraft account), are not affected.
- **Later `/admin`** — the bot asks for the PIN. The message containing it is deleted from the
  chat immediately. After a correct PIN the panel stays open until it has been idle for
  `session-timeout-minutes`; the **🔒 Lock panel** button closes it at once.
- **Brute-force lockout** — after `max-attempts` wrong PINs in a row (3 by default) PIN entry is
  locked. The first lockout lasts `lockout-seconds`; every further lockout in a row is
  `lockout-growth` times longer than the previous one, up to `lockout-max-seconds`. With the
  defaults (300 s, ×2, 24 h cap): 5 min, 10 min, 20 min, 40 min, 1 h 20 min, 2 h 40 min, … 24 h.
  The escalation starts over after `lockout-level-reset-minutes` without a new lockout, and a
  correct PIN resets it at once. Set `lockout-growth: 1.0` for a fixed lockout length. Failures
  that are older than one base lockout period stop counting. Triggering a lockout raises an alert:
  - if the targeted admin is **online in-game** (their Telegram is linked to the account): a red
    chat message, a title, and a two-tone siren for `siren-seconds`;
  - otherwise **all admins** are notified — in-game chat for every online `tgauth.admin` player
    and a Telegram message to everyone in `telegram.admin-ids` plus the linked Telegram of each
    online admin.
- **Forgotten PIN** — run `/tgauth pinreset <player|telegramId>` from the console or in-game; the
  admin sets a new one on their next `/admin` (and, if they log in again before that, is frozen
  until they do). An admin who is already logged in is not re-frozen mid-session. Other admins
  are told about the reset.

The PIN is stored only as a salted PBKDF2-HMAC-SHA256 hash (120,000 iterations). With a 4-digit
PIN an attacker who steals the database could still brute-force it offline, so prefer 6+ digits.

Once in, the panel offers:
- **Search player** — reply with a Minecraft name to open its account view.
- **List accounts** — paginated browse of every linked account, 5 per page, tap a name to open
  its account view.

The account view shows Telegram ID, @username, and premium status, with action buttons:

| Button | Effect |
|---|---|
| 🔗 Unlink | Remove the Telegram link (asks to confirm first) |
| ⭐ Un-premium | Clears TgAuth's own premium flag and runs FastLogin's `/unpremium <name>` (if FastLogin is installed) so it drops them from its premium list too |
| 👢 Kick | Prompts for a reason, then kicks if online |
| 🔨 Ban | Prompts for a reason, then a duration (`1s`/`5m`/`2h`/`7d`/`p` for permanent) |
| ♻ Unban | Removes a name-ban |
| ⚠ Warn | Prompts for a reason, then a duration (same format, `p` = no expiry) |

Kick asks for a reason only. Ban and Warn both ask for a reason, then a duration —
`1s`/`5m`/`2h`/`7d`/`w` for weeks, or `p`/`perm`/`permanent`.

Each action can run a configured command instead of TgAuth's built-in Bukkit behavior, for
servers with a dedicated punishment plugin (LiteBans, AdvancedBan, etc.):

```yaml
admin-commands:
  kick-command: ""
  ban-command: ""       # used when duration is 'p' (permanent)
  tempban-command: ""   # used when a real duration is given
  unban-command: ""
  warn-command: ""      # used when duration is 'p'
  tempwarn-command: ""  # used when a real duration is given
```

Ban and Warn each have separate permanent/temporary commands since many punishment plugins use
genuinely different commands for the two (e.g. LiteBans' `/ban` vs `/tempban`), not just a
different argument. Placeholders: `%player%`, `%reason%`, `%duration%` (the raw text you typed
in the bot - make sure it matches what your punishment plugin expects). Leave any of these
blank to use TgAuth's built-in behavior for that action instead (Warn's built-in version just
messages the player in-game and doesn't actually track/enforce the duration itself).

Every action in the panel also notifies other admins in Telegram: `[TgAuth] <admin's Minecraft
name> <what happened>` is sent to everyone in `telegram.admin-ids` and every online player with
`tgauth.admin`, except whoever performed the action.

Get your numeric Telegram ID from a bot like `@userinfobot`.

## Storage: SQLite, MySQL or MariaDB

All three backends are accessed through a [HikariCP](https://github.com/brettwooldridge/HikariCP)
connection pool. `storage.type` selects one:

| Type | Driver | Notes |
|---|---|---|
| `sqlite` | bundled | Default. A local file; WAL mode, pool of 4. |
| `mysql` | Connector/J, **provided by Paper** | Not bundled. If your server lacks it, TgAuth says so and suggests `mariadb`. |
| `mariadb` | MariaDB Connector/J 3.5, **bundled** | Also works against MySQL servers. |

**Setting up MySQL/MariaDB**

1. Create an empty database and a user for it, e.g.
   `CREATE DATABASE tgauth CHARACTER SET utf8mb4; CREATE USER 'tgauth'@'%' IDENTIFIED BY '…'; GRANT ALL ON tgauth.* TO 'tgauth'@'%';`
2. Fill in `storage.type` and the `storage.mysql` section, then restart. TgAuth creates its two
   tables (`<prefix>linked_accounts`, `<prefix>admin_pins`) itself.
3. **Existing players:** run `/tgauth migrate` once (console or an admin). It copies the accounts and
   PINs from the old SQLite file; rows already present are skipped, so it is safe to repeat. Without
   this step everyone would have to link again.

Connection defaults: 5 s connect timeout, 20 s socket timeout (so a stuck query can't hang forever),
and no TLS unless `use-ssl: true` — turn it on whenever the database is not on the same machine.
Anything else goes into `storage.mysql.properties` (driver option names), which overrides the
defaults; MySQL 8 without TLS typically needs `allowPublicKeyRetrieval: "true"` there.

**Things to know**

- Some admin commands (`unlink`, `forcelink`, `userinfo`, `pinreset`) and the optional trusted-IP
  check run their queries on the server thread. With a remote database, keep it close (low latency);
  the timeouts above bound a stall but do not remove it.
- Several servers can share one database, but a Telegram bot token can only be polled by **one**
  process at a time. Run the bot on one server (or use one bot per server), otherwise Telegram answers
  with `409 Conflict`.


## Update checker

TgAuth looks at the latest release of
[googllle9g/TgAuth-plugin](https://github.com/googllle9g/TgAuth-plugin/releases) shortly after
startup and then every `update-checker.check-interval-hours`. If a newer version exists, it is
logged once, and admins (`tgauth.admin`) get a clickable in-game message — right after they log in,
or immediately if they are already online when the check finishes. `/tgauth update` checks on
demand.

The request is asynchronous (`HttpClient.sendAsync`, 5 s connect / 10 s request timeout): the
server thread is never blocked, only the final chat message is sent from the main thread. Network
errors or GitHub rate limits are silent (FINE log level) so they never clutter the console.

## FastLogin integration

FastLogin doesn't run any Mojang verification at all unless it's behind a proxy or has a
registered "auth plugin" hook — otherwise you'll see `No auth plugin were found by this plugin`
in its logs and it does nothing. TgAuth registers itself as that hook automatically on startup
(via reflection, so it doesn't need FastLogin as a build dependency and tolerates version
differences). Run `/tgauth fastlogin` to check the status of everything below.

Three settings in **FastLogin's own `config.yml`** (not TgAuth's) matter for a correctly
working hybrid (premium + cracked) server — verified against
[FastLogin's actual config.yml comments](https://github.com/games647/FastLogin/blob/main/core/src/main/resources/config.yml):

- **`autoRegister: true`** — without it, FastLogin never checks a brand-new
  (never-before-registered) name's premium status at all. If you've avoided this before because
  of password issues with LoginSecurity/AuthMe (it force-generates a real login password there),
  that doesn't apply to TgAuth: our `forceRegister` implementation ignores the password argument
  entirely — TgAuth has no concept of passwords, everything goes through Telegram.
- **`premiumUuid: true`** — without it, FastLogin does **not** switch a verified-premium
  player's effective UUID to their real Mojang UUID; they keep the same offline/cracked UUID
  regardless of verification. TgAuth's `auth.migrate-link-by-username` only has any effect when
  a UUID actually changes between a cracked and a premium login for the same name — without
  this setting, that never happens, so the feature is a no-op either way.

Requires `online-mode: false` in `server.properties` — FastLogin performs its own per-player
Mojang check.

## Security

- **Link codes are rate-limited**, per Telegram account and globally, against brute-forcing the
  6-digit code.
- **Confirm/Reject buttons use a random 128-bit token** — not guessable, not replayable.
- **All SQL is parameterized.**
  **Narrow exception:** if an admin (or a player with `fastlogin.bukkit.command.premium`)
  manually runs FastLogin's `/premium <name>` for a name that already has a squatted TgAuth
  link, and the real owner happens to be the one connecting at that moment, a genuinely
  different UUID *can* appear — and this setting would then migrate the link, squatted one
  included. Check `/tgauth userinfo <name>` before manually running `/premium` for a name you
  didn't link yourself.
- Player data migration overwrites any playerdata/advancements/stats already present for the
  destination UUID by default (`migration-overwrite-existing-data: true`) — safe on a fresh
  server, or one where TgAuth/FastLogin were set up from day one, since a migration only ever
  targets a UUID being recognised as premium for the first time. Turn it off if you added this
  setup to an already-running server where players already had real progress under their own
  premium UUIDs.

Report anything else you find.

## Third-party components

Bundled (shaded) into the release jar:

- [TelegramBots](https://github.com/rubenlagus/TelegramBots) — MIT License — © Ruben Bermudez
- [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) — Apache License 2.0 — © Xerial Project
- [HikariCP](https://github.com/brettwooldridge/HikariCP) — Apache License 2.0 — © Brett Wooldridge
- [MariaDB Connector/J](https://github.com/mariadb-corporation/mariadb-connector-j) — LGPL 2.1 — © MariaDB Corporation Ab (bundled, unmodified, not relocated)
- [Gson](https://github.com/google/gson) — Apache License 2.0 — © Google
- [OkHttp](https://github.com/square/okhttp) — Apache License 2.0 — © Square, Inc.

Compiled against only (not bundled): [Paper](https://papermc.io/) (`paper-api`).

## License

See [`LICENSE`](LICENSE).
