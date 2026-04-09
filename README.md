# mcgame - PokerTable plugin

Paper plugin for Minecraft 1.21.x that adds a multiplayer poker-style gambling table with item-based chips.

## Chip values (configurable)

No ore blocks are used. Only these chip items count:

- Iron Ingot = `1` credit (default)
- Gold Ingot = `8` credits (default)
- Emerald = `64` credits (default)
- Diamond = `512` credits (default)
- Netherite Scrap = `2048` credits (default)
- Netherite Ingot = `8192` credits (default)

Default progression summary:
- 8 iron = 1 gold
- 8 gold = 1 emerald
- 8 emerald = 1 diamond
- 4 diamonds = 1 netherite scrap
- 4 scraps = 1 netherite ingot

You can change these in `plugins/PokerTable/config.yml`:

```yml
chips:
  iron_ingot: 1
  gold_ingot: 8
  emerald: 64
  diamond: 512
  netherite_scrap: 2048
  netherite_ingot: 8192
```

## Commands

- `/table` - teleport to configured table location (if set) and sit at table
- `/table leave` - leave the table
- `/table start` - force start a round (if enough players are seated)
- `/table value` - shows your current total chip value
- `/table setlocation` - admin command to set the table join location
- `/table reload` - admin command to reload config and chip values live

## Gameplay

- Supports multiple players at one table (minimum 2, maximum 8)
- Uses blinds (default small blind 16, big blind 32 credits)
- Deals Texas Hold'em style cards:
  - 2 private cards per player
  - 5 community cards
- Best 5-card hand from 7 cards wins the pot
- Split pot on exact hand ties
- Players that disconnect are automatically removed from the table

## Build

Requires Java 21 and Maven:

```bash
mvn -DskipTests package
```

Output jar:

`target/poker-table-1.0.0.jar`

Place this jar in your Paper server `plugins/` directory and restart the server.
