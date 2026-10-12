package com.github.nullptr7

import zio.*

final case class GraphDbSettings(location: String)

object GraphDbSettings:
  val config: Config[GraphDbSettings] =
    Config.string("location").map(GraphDbSettings.apply).nested("graphdb")

  val layer: ZLayer[Any, Config.Error, GraphDbSettings] =
    ZLayer.fromZIO(ZIO.config(config))
