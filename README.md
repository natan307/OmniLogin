<p align="center">
  <a href="https://ko-fi.com/natanezra"><img src="https://img.shields.io/badge/Support%20me%20on-Ko--fi-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white" alt="Support me on Ko-fi"></a>
  <a href="https://discord.com/users/1211125229770903654"><img src="https://img.shields.io/badge/By%20Natan307-3020FF?style=for-the-badge&logo=star&logoColor=white" alt="Natan307"></a>
</p>

**⚠️ This plugin will only be released or published in the next few days. ⚠️**

# OmniLogin

Microsoft and Ely.by login for **offline-mode servers**, with name protection and a built-in password login.

* **Premium (Microsoft and Ely.by):** the session is verified, so nobody can pretend to be an official account.
  Skins show up and verified players join without typing anything.
* **Cracked:** players without an account can join, but only with a name that is **not** registered on Microsoft or
  Ely.by. Registered names are refused with a clear message.
* **Password login (optional):** cracked players `/register` once and `/login` on every join. SQLite or MySQL.

Supports Paper, Folia, Purpur, Spigot, Bukkit and their forks (1.21 and newer), plus Velocity and BungeeCord.
No ProtocolLib, no extra port, no extra plugins.

MIT License. Original work by Natan307.

## Paper / Folia / Purpur / Spigot / Bukkit (single server)

1. `server.properties`: `online-mode=false`
2. Put `OmniLogin-<version>.jar` in `plugins/` and start the server.

That is all: no proxy, no ProtocolLib, no extra port, nothing in `spigot.yml`.

Optional: if you also set `settings.bungeecord: true` in `spigot.yml` (this is only a server setting, **not** a proxy),
OmniLogin passes the verified player's real skin to the server, and `login.use-premium-uuid: true` makes verified
players use their real Microsoft/Ely.by UUID. Without it (plain mode) verified players keep their offline UUID, so
existing player data keeps working, and skins come from a skin plugin such as SkinsRestorer.

OmniLogin hooks into the server's network layer and checks every connection **before** the server sees it. Anything
that was not checked is refused, and if OmniLogin cannot hook into the server it stops the `online-mode=false` server
(`shutdown-if-unsafe` in `config.yml`) instead of leaving it open to name spoofing.

Commands: `/register`, `/login`, `/changepassword`, and for admins (`omnilogin.admin`) `/omnilogin status|check <name>|about`,
`/omniauth unregister <name>`, `/omniauth setpassword <name> <password>`.

## Bedrock players (Geyser / Floodgate)

OmniLogin detects Geyser and Floodgate by itself (`geyser.support: auto`).

* Connections that come from Geyser's direct (in-process) connection are trusted: they cannot come from the
  internet, so name protection is skipped for them and the original handshake is passed through untouched.
* Floodgate players (UUIDs that start with zeros) are verified by Floodgate with Xbox, so they skip the password login.
* Without Floodgate, Bedrock players count as cracked players (`/register`, `/login`). A Bedrock name that equals a
  registered Java name is refused; Floodgate's `.Name` prefix avoids that.
* With Geyser installed, `spigot.yml` must have `settings.bungeecord: false` (Floodgate's data travels in the
  handshake). OmniLogin stops the server with a clear message if it is `true`.
* If Bedrock players are kicked with "connection could not be verified", set `geyser.allow-unregistered-loopback: true`.

## Velocity

Put the jar in Velocity's `plugins/`, set `online-mode = true` in `velocity.toml`. To verify **Ely.by** accounts too,
start Velocity with:

    java -Dmojang.sessionserver=http://127.0.0.1:8765/session/minecraft/hasJoined -jar velocity.jar

Without the flag Microsoft accounts and cracked players still work; Ely.by-only names are refused.
Backend servers: `online-mode=false` with Velocity forwarding, and set `mode: backend` in OmniLogin's `config.yml`
on each backend (it then only runs the password login).

## BungeeCord

Put the jar in `plugins/`. BungeeCord cannot change its session server, so **Ely.by accounts cannot be verified**
there (use Velocity for that). Microsoft login and cracked name protection work. Backends: `mode: backend`.

## Password login

`auth.ui: dialog` (default) shows a **Minecraft dialog window** with input fields for `/register` and `/login`
(Paper 1.21.7+, clients 1.21.6+). Players on older clients, and servers without the Dialog API, use the chat commands
instead; the commands always work. Dialog text fields show what is typed (Minecraft has no hidden-input mode), and
a chat `/login <password>` is written to the server log, so prefer the dialog.

`auth.enabled: true|false` in `config.yml`. Storage `sqlite` (a file in the plugin folder) or `mysql`/MariaDB.
Passwords are hashed with PBKDF2-HMAC-SHA256. Verified Microsoft/Ely.by players skip it. In `mode: backend`, players
with a real (non-offline) UUID are treated as verified, so set `login.use-premium-uuid: true` in the proxy's config.

## Troubleshooting

Set `debug: true` in `config.yml` and restart: every login is then logged step by step (`[debug]` lines: handshake,
name check, decision, session check, result). A step that takes longer than `login.step-timeout-ms` ends with a kick
message instead of a client timeout. Turn `debug` off again afterwards.

## Limits

* Premium login needs a Minecraft **1.19.3+** client (older clients can still join with a free name).
* Geyser support is new and has only been tested with simulated connections.
* The password login is for Paper/Spigot servers; Velocity and BungeeCord run name protection only.

## Build

Requires Java 21 (Minecraft 1.21+ servers already need it).

    mvn clean package

The jar is `target/OmniLogin-<version>.jar` (one jar for all platforms).

## License

MIT, see `LICENSE`. Third-party notices: `THIRD-PARTY.md`.
