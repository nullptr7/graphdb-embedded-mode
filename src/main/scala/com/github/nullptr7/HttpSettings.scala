package com.github.nullptr7

import zio.*

final case class HttpSettings(port: Int)

object HttpSettings:
  val config: Config[HttpSettings] =
    Config.int("port").map(HttpSettings.apply).nested("http")

  val layer: TaskLayer[HttpSettings] =
    ZLayer.fromZIO(ZIO.config(config))
