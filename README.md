# tic-tacs

Tic-TACS is an experimental reimplementation of Minecraft's chunk loading engine (a.k.a "TACS"). Tic-TACS reduces the overhead for the chunk loading process, with special focus to lessen the impact that chunk loading has on the server tick rate. 

Tic-TACS additionally enables optional multithreading of chunk loading which can bring significant improvement in generation speed, as well as allowing for much higher chunk view distances. 

It is important to note that this mod is **currently still experimental**! Although the chances of fully corrupting a world are pretty low, the game may freeze or crash which could cause loss of progress. 

## configuration

The mod is configured through the `config/tictacs.json` file, which should look something like:
```json
{
  "version": 2,
  "thread_count": 2,
  "max_view_distance": 32,
  "feature_generation_radius": 2,
  "debug": {
    "chunk_levels": false,
    "chunk_map": false
  }
}
```

For most cases, the only useful properties here will be `thread_count` and `max_view_distance`.

`thread_count` controls the number of threads that will be used for chunk generation. It is important to note that setting this anything beyond 4 is unlikely to make any difference to performance, and setting it too high can be instead detrimental! Chunk generation is still additionally limited by the lighting engine, which Tic-TACS does not multithread.

`max_view_distance` controls the maximum view distance that the game can support. This won't directly change render distance, but will change the maximum value for the vanilla render distance slider. Values beyond 100 may cause issues, but this restriction may be solved in the future.

## downloading
You can find semi-stable builds of Tic-TACS in the [GitHub releases](https://github.com/Gegy/tic-tacs/releases). 

Alternatively, you can find much less stable builds from most recent commits on [my Jenkins](https://ci.gegy.dev/job/tic-tacs/).
