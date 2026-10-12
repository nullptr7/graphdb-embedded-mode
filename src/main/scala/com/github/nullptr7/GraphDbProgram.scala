package com.github.nullptr7

import zio.*
import zio.http.Server

import java.nio.file.Paths

object GraphDbProgram:
  val run: ZIO[GraphDb & GraphDbSettings & Server, GraphDbError, Unit] =
    for
      settings <- ZIO.service[GraphDbSettings]
      database <- ZIO.service[GraphDb]
      path     <- ZIO.attempt(Paths.get(settings.dataFile)).mapError(GraphDbError.apply)
      _        <- database.load(path)
      _        <- ZIO.logInfo(s"Loaded RDF data from ${path.toAbsolutePath}")
      _        <- GraphDbHttp.serve
    yield ()
