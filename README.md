# tic-tacs

Tic-TACS is a **very experimental** reimplementation of Minecraft's engine for asynchronously generating and loading chunks with significantly less overhead as well as bringing the ability to operate safely over multiple threads. 

The name "tic-tacs" comes from the Minecraft class `ThreadedAnvilChunkStorage` (TACS for short) under [Yarn](https://github.com/FabricMC/yarn/). This is the monolithic class responsible for most of the asynchronous processing work, and it is known for being very chaotic.


<p align="center">
  <img src="https://i.imgur.com/CyA190s.png" width="250" />
</p>
