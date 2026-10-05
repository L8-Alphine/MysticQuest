# MysticQuests Studio

The Studio is a web page for building quests: quests and their steps and objectives, dialogue, story
state, puzzles, media and cutscenes. It edits the same package files you would write by hand
([creating quests by hand](creating-quests.md)), so you can mix both ways of working.

Nothing you change reaches players until you **publish**. Edits go into a private draft. Publishing
checks the draft the same way `/mquest reload` would, copies it over the live content and reloads.
Every release is kept, so you can roll back to any of them.

## Turning it on

The Studio is off by default. In `mods/MysticQuests/config.json`:

```json
"studio": {
  "enabled": true,
  "bind": "127.0.0.1",
  "port": 8765,
  "publicUrl": "",
  "environment": "development"
}
```

| Key | Set it to |
|---|---|
| `enabled` | `true` to start the Studio with the server. |
| `bind` | The address it listens on. Keep `127.0.0.1` (this machine only) unless a reverse proxy with HTTPS sits in front. |
| `port` | Any free port. |
| `publicUrl` | The address people open, for example `https://studio.example.net/`. Leave it blank to use `http://<bind>:<port>/`. With an `https` address, the sign-in cookie is marked secure. |
| `environment` | `development`, `staging` or `production`. The Studio shows it on every page and on the publish button, so nobody publishes to production by mistake. |

Restart the server after changing these. The log says where the Studio is listening.

> **Never expose the Studio to the internet over plain HTTP.** To use it from other machines, put
> a reverse proxy (Caddy, nginx, Traefik) with HTTPS in front, keep `bind` at `127.0.0.1`, and set
> `publicUrl` to the proxy's address. Behind a proxy, every client shares the proxy's address, so
> repeated wrong sign-in codes from anyone pause sign-in for everyone for a few minutes.

## Signing in

1. In game, run `/mquest studio login`.
2. Click the link in chat, or open the Studio and type the code.

The code works once, for five minutes, and only for you. You stay signed in for up to 12 hours, or
2 hours without activity. What you can do is checked against your permissions on every action,
so removing a permission takes effect immediately.

`/mquest studio` without `login` still opens the in-game Quest Studio.

### With a MysticIdentity account

If the network runs MysticIdentity, people can also sign in with their MysticIdentity account,
from the **Sign in with MysticIdentity** button. The account must have a Hytale profile linked; the
Studio then checks that player's permissions, exactly as with a code.

1. In MysticIdentity's Owner panel, under **Applications**, create an application for the Studio
   with one client. Its redirect is the Studio's address plus `auth/callback`, for example
   `https://studio.example.net/auth/callback`; its scopes are `openid identity.read hytale.read`.
   Copy the client secret: it is shown once.
2. On the game server, put the secret in the environment variable `MYSTICQUESTS_STUDIO_CLIENT_SECRET`
   (or the one named by `clientSecretEnv`). It is never read from `config.json`.
3. In `config.json`:

   ```json
   "studio": {
     "enabled": true,
     "publicUrl": "https://studio.example.net/",
     "identity": {
       "enabled": true,
       "issuer": "https://id.example.net",
       "clientId": "<the client id>",
       "clientSecretEnv": "MYSTICQUESTS_STUDIO_CLIENT_SECRET"
     }
   }
   ```

4. Restart. The log confirms the redirect to register, or names what is missing. If anything is
   missing, sign-in codes keep working.

## Permissions

| Permission | Lets you |
|---|---|
| `mysticquests.studio.login` | Sign in. Removing it ends the user's sessions at their next action. |
| `mysticquests.studio.view` | See everything in the Studio: content, validation, history and audit. Any permission below also grants this. |
| `mysticquests.studio.dialogue` | Change conversations. |
| `mysticquests.studio.audio` | Change speakers, media (voice lines, music, sounds) and cutscenes. |
| `mysticquests.studio.puzzles` | Change puzzles. |
| `mysticquests.studio.triggers` | Change world overlays (doors, seals and bridges per audience). |
| `mysticquests.studio.edit` | Change everything above, and quests, rewards, events, conditions, schemas and package settings. Also: discard the draft, load an old release. |
| `mysticquests.studio.publish` | Put the draft live. |
| `mysticquests.studio.live` | Watch live sessions: who is online, their story state, the runtime's limits and slowest operations. Not granted by `edit`. |
| `mysticquests.studio.admin` | Everything, plus `/mquest studio status` and `revoke`. `mysticquests.admin` also grants everything. |

Permissions are checked per section of content, not per file. A writer with only
`mysticquests.studio.dialogue` can save a file that holds both quests and conversations, as long as
only the conversations changed. If the quests changed too, the save is refused and logged.

Typical setups:

- **Writer:** `login` and `dialogue`.
- **Builder:** `login`, `puzzles` and `triggers`.
- **Quest designer:** `login` and `edit`.
- **Release manager:** `login`, `view` and `publish`.

## Working in the Studio

| Page | For |
|---|---|
| Overview | How many files the draft changes, the latest release, warnings (files that do not parse, live files edited outside the Studio), and the package tree: packages nest as folders, so a season can hold chapters and a chapter its acts. |
| Quests | Every quest in every package. A form for the name, journal text, steps and objectives; everything else (start conditions, events, rewards) as JSON. With no quest selected, the **quest map** shows which quests start or unlock which. **Try it** steps through the quest as it is in the form, saved or not: complete objectives and see what the tracker shows at each point. |
| Dialogue | Conversations, their lines and choices. The flow view marks lines that can never be reached and choices that lead to a missing line. |
| Story state & puzzles | Tag and variable schemas as tables (scope, type, default, milestone), with how often each is used across the draft, so unused ones stand out. A **Locales** tab shows which voice lines are recorded in which languages and which have subtitles. **Puzzles** as a form: inputs and their trigger volumes, how many are dealt per player or party, the rule and its settings, and what happens on each input, mistake, reset and solve; **Try it** plays the rule in the browser, dealing inputs at random as a player would get them. **Cutscenes** on a timeline, with their steps, which steps are cosmetic, and what runs when the scene ends. World overlays, speakers and media as JSON with a one-line summary. |
| World | Every trigger volume and story NPC the draft names, and where: puzzle inputs, objectives, overlays, spawns, claims and conversation NPCs. A name used only once is flagged, as it may be a typo. |
| Audio | Upload sound clips, listen to them, copy a ready media entry, and build the sound pack. See [Audio](#audio). |
| Files | Any draft file as text. Use it for anything the forms do not cover, and to keep YAML comments. |
| Validate | The draft checked exactly as `/mquest reload` would check it, with each problem's location. |
| Publish & history | What publishing would change, the publish button, and every release with its changes. **Load into draft** restores an old release into the draft; publishing it makes a new release. |
| Live sessions | Who is online and how many stories each is in; the runtime's sizes against their limits, counters and slowest operations; which optional mods are active; and, for one player, everything `/mq debug` shows. Read-only and refreshed every 10 seconds. At most 10 people can watch at once. To fix something, use the in-game commands, which are audited. |
| Audit log | Every sign-in, save, publish and refused action. |

### Things to know

- **Forms rewrite the file.** Saving through a form writes the whole file again, which drops YAML
  comments. The Studio asks first when a file has comments. Edit in **Files** to keep them.
- **Two people, one file.** If someone else saved a file after you opened it, your save is refused
  instead of overwriting their work. Reload and make your change again.
- **Edits outside the Studio.** If someone changes the live files by hand or with the in-game
  editor after your draft was made, publishing stops and lists those files. Either **Discard draft**
  to start again from the live content, or publish with *Replace the live edits*.
- **A refused release changes nothing.** If the server refuses the reload, the previous files are
  put back and reloaded, and no release is recorded.

## Development, staging and production

Each server runs its own Studio, and `environment` labels which one you are on. To move content
up, for example from a development server to production:

1. On the development server, publish, then **Download** the release (Publish & history).
2. On the production server, **Import a release from another server** with that zip. It replaces the
   production *draft* only; players see nothing yet.
3. Check the changes and **Validate**, then **Publish** on production. It becomes production's next
   release, with its own history and rollback.

Importing needs `edit`; publishing needs `publish`, so the person who promotes to production can be
someone other than the person who wrote the content. A bundle holds only content files at valid
paths; anything else in the zip is refused.

## Audio

Upload voice lines, sound effects, ambience, stingers, UI and cinematic sounds, and story media can
play them.

1. **Upload** an Ogg Vorbis (`.ogg`) file, up to 8 MiB, with a clip name and a kind. Hytale plays
   only Ogg Vorbis, and the server cannot convert WAV, FLAC or MP3, so export to `.ogg` first
   (Audacity: *File → Export → Ogg Vorbis*). Use mono for anything heard from an NPC or a place;
   the Studio warns about stereo. The upload is kept as the master.
2. **Use it:** each clip gets a sound event id, `MysticQuests_<Kind>_<name>`. **Copy media entry**
   puts a ready entry on the clipboard for the `media` section, for example
   `{ id: mypack:warden_greeting, kind: voice, sound: MysticQuests_Voice_warden_greeting }`.
3. **Build sound pack** (needs `audio` and `publish`) writes every clip into the
   `MysticQuests-Generated` pack beside the plugin in `mods/`: the sound files under
   `Common/Sounds/MysticQuests/` and one sound event each under `Server/Audio/SoundEvents/MysticQuests/`.
   Clips you deleted are removed from it.
4. **Restart the server** to load new and changed sounds. Until then, media naming a new sound shows
   its subtitle and logs a `MISSING_ASSET` warning, as with any missing sound.

The server loads the pack only if it loads mods by default (`DefaultModsEnabled`); otherwise enable
`org.hyzionstudios:mysticquests-generated` in the server's mod settings. Music is not built here:
the music system plays music containers, which still come from an asset pack you make yourself.

## Where the Studio keeps things

Under `mods/MysticQuests/studio/`:

| Path | Holds |
|---|---|
| `workspace/` | The draft: a copy of `packages/` and `templates/`. |
| `releases/00000/` | The content as it was before the Studio's first release. |
| `releases/00001/`, ... | Each release exactly as it went live. |
| `releases.jsonl` | The release history, one line per release. It is only ever appended to. |
| `audit.jsonl` | The audit log, append-only. |
| `audio/` | Uploaded clips as uploaded, and `index.json` describing them. The built pack is a copy. |

Back these up with the rest of `mods/MysticQuests/`. Deleting `workspace/` throws away the draft;
the Studio makes a new one from the live content.

## Not built yet

- Changing a player's story from the Studio. Live Sessions only shows it; the in-game
  `/mquest narrative` commands make the changes.
- Converting audio: uploads must already be Ogg Vorbis.
- Music containers for the music system.
- Loading a rebuilt sound pack without a restart.

## Working on the Studio itself

`./gradlew studioDev` runs the Studio without a game server, over a copy of `examples/packages` in
`build/studio-dev`, and prints a sign-in link. Every permission is granted, and publishing copies
files without reloading anything. The pages are plain JavaScript modules in
`src/main/resources/studio-web/`, with no build step; run `./gradlew processResources` and reload
the page after changing them.
