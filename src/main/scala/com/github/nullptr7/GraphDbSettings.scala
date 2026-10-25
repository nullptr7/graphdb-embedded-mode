package com.github.nullptr7

import zio.*

final case class GraphDbSettings(location: String, dataFile: String)

object GraphDbSettings:
  val config: Config[GraphDbSettings] =
    (Config.string("location") zip Config.string("data-file"))
      .map(GraphDbSettings.apply)
      .nested("graphdb")

  val layer: TaskLayer[GraphDbSettings] =
    ZLayer.fromZIO(ZIO.config(config))
