# Third-party software

- **bStats** (https://bstats.org, https://github.com/Bastian/bStats-Metrics) - anonymous usage statistics,
  licensed under the MIT License. It is shaded into the Paper/Spigot jar under `dev.omnilogin.libs.bstats`.
  Statistics can be switched off with `bstats: false` in OmniLogin's config.yml or globally in
  `plugins/bStats/config.yml`.

Netty, the Paper/Spigot API, the Velocity API and the BungeeCord API are provided by the server or proxy at runtime
and are not part of this jar.
