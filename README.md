# MiraLeaderboards

MiraLeaderboards is the generic ranking engine for the Mira Paper server suite. It lets other plugins publish named scores into persistent leaderboards instead of each feature maintaining its own ranking implementation.

## Download

[**Download MiraLeaderboards v0.1.1**](https://github.com/FiveSOCE/Mira-Leaderboards/releases/download/v0.1.1/MiraLeaderboards-0.1.1.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer
- PlaceholderAPI optional
- MiraSeasons optional for automatic seasonal-board resets

## How MiraLeaderboards Works

Any number of named leaderboard IDs can be created. v0.1.1 stores each entry by a stable ID independently from its display name, so player renames and faction display-name changes do not create duplicate identities. The original 0.1.0 name-based API remains compatible, while source plugins can publish a stable ID, display name, score and source label through the expanded API.

Boards may be `ALL_TIME` or `SEASONAL`. Seasonal boards remember the authoritative MiraSeasons ID, snapshot the outgoing rankings and automatically clear into the new season when MiraSeasons starts a different season. All-time boards are never reset by that lifecycle.

Other Mira plugins can publish scores through the public `MiraLeaderboardsApi`. The intended publisher sources are MiraFactions/FTop, MiraPinata, MiraBounties, MiraPlaytime, MiraOutposts and MiraCrates, allowing those plugins to contribute rankings without owning another leaderboard engine.

MiraLeaderboards keeps a configurable cached Top N for fast PlaceholderAPI/NPC refreshes. Automatic and manual snapshots store historical Top N state in `snapshots.yml`; current rankings expose score change and rank change relative to the most recent snapshot. Text and GUI pagination use MiraCore's shared pagination service.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/leaderboard <board> [page]` | None required | Shows a leaderboard as paginated text. |
| `/mlb set <board> <entry> <score>` | `miraleaderboards.admin` | Sets an entry's score. |
| `/mlb add <board> <entry> <delta>` | `miraleaderboards.admin` | Adds to an entry's score. |
| `/mlb remove <board> <entry>` | `miraleaderboards.admin` | Removes an entry from a board. |
| `/mlb clear <board>` | `miraleaderboards.admin` | Clears all entries from a board. |
| `/mlb publish <board> <stable-id> <display-name> <score> [source]` | `miraleaderboards.admin` | Publishes a stable-ID entry using the same model exposed to other plugins. |
| `/mlb scope <board> <all_time\|seasonal>` | `miraleaderboards.admin` | Configures whether a board is permanent or resets with MiraSeasons. |
| `/mlb snapshot <board\|all>` | `miraleaderboards.admin` | Captures a manual ranking snapshot. |
| `/mlb history <board>` | `miraleaderboards.admin` | Shows recent snapshot history. |
| `/mlb gui <board> [page]` | `miraleaderboards.admin` | Opens the cached, paginated leaderboard GUI with delta information. |
| `/mlb list` | `miraleaderboards.admin` | Lists known board IDs, scopes, entry counts and seasonal ownership. |

Aliases: `/lb` for `/leaderboard`, `/mleaderboard` for `/mlb`.

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miraleaderboards.admin` | OP | Allows administrative score and leaderboard management. |


## Storage and Migration

- `leaderboards.yml` stores board scope, season ID and stable-ID entries.
- Stable IDs are encoded as safe YAML storage keys; the original ID remains stored as data.
- Existing 0.1.0 `boards.<board>.<name>: score` data is migrated in memory and written into the new model on the next save.
- `snapshots.yml` stores bounded historical snapshots. The history limit and snapshot cadence are configurable.

## PlaceholderAPI

Existing Top placeholders remain supported:

- `%miraleaderboards_<board>_top_1_name%`
- `%miraleaderboards_<board>_top_1_score%`
- `%miraleaderboards_<board>_top_1_formatted%`

v0.1.1 additionally supports Top 1-100 fields:

- `id`
- `delta`
- `rank_delta`
- `source`

Player-context fields use the player's UUID first and fall back to their name:

- `%miraleaderboards_<board>_player_rank%`
- `%miraleaderboards_<board>_player_score%`
- `%miraleaderboards_<board>_player_formatted%`
- `%miraleaderboards_<board>_player_delta%`
- `%miraleaderboards_<board>_player_rank_delta%`

Board metadata:

- `%miraleaderboards_<board>_scope%`
- `%miraleaderboards_<board>_season%`
- `%miraleaderboards_<board>_snapshot_age%`

## API / Publisher Model

The original `MiraLeaderboardsApi` methods remain available. v0.1.1 adds stable-ID publishing, additive publishing, ranked entries with deltas, board scope configuration, rank lookup and snapshot access. The API is registered through both Bukkit services and MiraCore.
