package com.github.nullptr7

import zio.*
import zio.logging.backend.SLF4J

import java.nio.file.Paths
import Rdf.*

object GraphDbApp extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val alice            = Subject("http://example.org/person/1")
  private val bob              = Subject("http://example.org/person/2")
  private val rdfType          = Predicate("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
  private val person           = IriObject("http://schema.org/Person")
  private val name             = Predicate("http://schema.org/name")
  private val aliceName        = Literal("Alice")
  private val bobName          = Literal("Bob")
  private val updatedAliceName = Literal("Alice Updated")

  private def program(database: GraphDb): IO[GraphDbError, Unit] =
    for
      _       <- database.addIri(alice, rdfType, person)
      _       <- database.addIri(bob, rdfType, person)
      _       <- database.addLiteral(alice, name, aliceName)
      _       <- database.addLiteral(bob, name, bobName)
      triples <- database.allTriples
      _       <- ZIO.logInfo(s"All triples: ${triples.mkString(", ")}")
      people  <- database.subjectsWithIri(rdfType, person)
      _       <- ZIO.logInfo(s"People: ${people.mkString(", ")}")
      _       <- database.replaceLiteral(alice, name, aliceName, updatedAliceName)
      _       <- database.removeIri(bob, rdfType, Some(person))
      names   <- database.select(Query("""
        PREFIX schema: <http://schema.org/>
        SELECT ?name WHERE { ?person schema:name ?name }
      """))
      _       <- ZIO.logInfo(s"Names after update: $names")
    yield ()

  def run: ZIO[Any, Nothing, Unit] =
    ZIO
      .scoped(GraphDb.scoped(Paths.get("repositories")).flatMap(program))
      .tapError(error => ZIO.logErrorCause("GraphDB operation failed", Cause.fail(error)))
      .orDie
