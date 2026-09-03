# MiraLeaderboards

MiraLeaderboards is the generic ranking engine for the Mira Paper server suite. It lets other plugins publish named scores into persistent leaderboards instead of each feature maintaining its own ranking implementation.

## Download

[**Download MiraLeaderboards v0.1.0**](https://github.com/FiveSOCE/Mira-Leaderboards/releases/download/v0.1.0/MiraLeaderboards-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- PlaceholderAPI optional

## How MiraLeaderboards Works

Any number of named leaderboard IDs can be created. Scores are persistent and can be set, incremented, removed or cleared administratively. Each board supports Top 100 ranking, paginated text output and a Top 10 podium-style GUI.

Other Mira plugins can publish scores through the public `MiraLeaderboardsApi`. PlaceholderAPI supports dynamic placeholders for any board ID, including rank name, raw score and formatted score. Data is stored in `plugins/MiraLeaderboards/leaderboards.yml`.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/leaderboard <board> [page]` | None required | Shows a leaderboard as paginated text. |
| `/mlb set <board> <entry> <score>` | `miraleaderboards.admin` | Sets an entry's score. |
| `/mlb add <board> <entry> <delta>` | `miraleaderboards.admin` | Adds to an entry's score. |
| `/mlb remove <board> <entry>` | `miraleaderboards.admin` | Removes an entry from a board. |
| `/mlb clear <board>` | `miraleaderboards.admin` | Clears all entries from a board. |
| `/mlb gui <board>` | `miraleaderboards.admin` | Opens the podium-style leaderboard GUI. |
| `/mlb list` | `miraleaderboards.admin` | Lists known leaderboard IDs. |

Aliases: `/lb` for `/leaderboard`, `/mleaderboard` for `/mlb`.

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `miraleaderboards.admin` | OP | Allows administrative score and leaderboard management. |
