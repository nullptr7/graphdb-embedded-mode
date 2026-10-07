package com.github.nullptr7

import zio.*
import zio.logging.backend.SLF4J

import java.nio.file.Paths

object GraphDbApp extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val alice   = "http://example.org/person/1"
  private val bob     = "http://example.org/person/2"
  private val rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
  private val person  = "http://schema.org/Person"
  private val name    = "http://schema.org/name"

  private def program(database: GraphDb): IO[GraphDbError, Unit] =
    for
      _       <- database.addIri(alice, rdfType, person)
      _       <- database.addIri(bob, rdfType, person)
      _       <- database.addLiteral(alice, name, "Alice")
      _       <- database.addLiteral(bob, name, "Bob")
      triples <- database.allTriples
      _       <- ZIO.logInfo(s"All triples: ${triples.mkString(", ")}")
      people  <- database.subjectsWithIri(rdfType, person)
      _       <- ZIO.logInfo(s"People: ${people.mkString(", ")}")
      _       <- database.replaceLiteral(alice, name, "Alice", "Alice Updated")
      _       <- database.removeIri(bob, rdfType, Some(person))
      names   <- database.select("""
        PREFIX schema: <http://schema.org/>
        SELECT ?name WHERE { ?person schema:name ?name }
      """)
      _       <- ZIO.logInfo(s"Names after update: $names")
    yield ()

  def run: ZIO[Any, Nothing, Unit] =
    ZIO
      .scoped(GraphDb.scoped(Paths.get("repositories")).flatMap(program))
      .tapError(error => ZIO.logErrorCause("GraphDB operation failed", Cause.fail(error)))
      .orDie
