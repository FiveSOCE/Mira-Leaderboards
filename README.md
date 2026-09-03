# MiraLeaderboards

Generic leaderboard engine for the Mira Paper 1.21.11 / Java 21 ecosystem.

## Download

Current release: **v0.1.0**

[**Download MiraLeaderboards v0.1.0**](https://github.com/FiveSOCE/Mira-Leaderboards/releases/download/v0.1.0/MiraLeaderboards-0.1.0.jar)

[View all releases](https://github.com/FiveSOCE/Mira-Leaderboards/releases)

## Features

- unlimited named leaderboards
- persistent scores
- score set/add/remove/clear administration
- Top 100 ranking
- paginated text views
- Top 10 podium-style GUI
- public `MiraLeaderboardsApi` through Bukkit ServicesManager
- dynamic PlaceholderAPI placeholders for any board

Other Mira plugins can publish scores to this engine instead of each maintaining a separate leaderboard implementation.

## PlaceholderAPI

For any board ID:

```text
%miraleaderboards_<board>_top_1_name%
%miraleaderboards_<board>_top_1_score%
%miraleaderboards_<board>_top_1_formatted%
```

Ranks 1 through 100 are supported.

## Commands

```text
/leaderboard <board> [page]
/mlb set <board> <entry> <score>
/mlb add <board> <entry> <delta>
/mlb remove <board> <entry>
/mlb clear <board>
/mlb gui <board>
/mlb list
```

## Data

```text
plugins/MiraLeaderboards/leaderboards.yml
```

## Requirements

- Paper 1.21.11
- Java 21
- PlaceholderAPI optional

## Building

```bash
gradle clean build
```

Output:

```text
build/libs/MiraLeaderboards-0.1.0.jar
```
