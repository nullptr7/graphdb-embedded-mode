package com.github.nullptr7

import zio.*
import zio.config.typesafe.*
import zio.logging.backend.SLF4J

object GraphDbApp extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    (Runtime.removeDefaultLoggers >>> SLF4J.slf4j) ++
      Runtime.setConfigProvider(ConfigProvider.fromResourcePath())

  def run: ZIO[Any, Nothing, Unit] =
    GraphDbProgram.run
      .provide(GraphDbSettings.layer, HttpSettings.layer, GraphDb.live, GraphDbHttp.server)
      .tapError(error => ZIO.logErrorCause("GraphDB operation failed", Cause.fail(error)))
      .orDie
