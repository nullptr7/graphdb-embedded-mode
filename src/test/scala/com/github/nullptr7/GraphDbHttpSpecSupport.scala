package com.github.nullptr7

import zio.Chunk
import zio.http.{QueryParams, Request, URL}

/** Reusable HTTP query fixtures for GraphDbHttp specs. */
private[nullptr7] final case class QueryInput(
    subject:   Option[String] = None,
    predicate: Option[String] = None,
    obj:       Option[String] = None,
    objectType: Option[String] = None,
    graph:     Option[String] = None
):
  def toRequest: Request =
    val parameters = List(
      subject.map("subject" -> _),
      predicate.map("predicate" -> _),
      obj.map("object" -> _),
      objectType.map("objectType" -> _),
      graph.map("graph" -> _)
    ).flatten

    val queryParams = parameters.map { case (key, value) => key -> Chunk.single(value) }.toMap
    Request(url = URL.root.copy(queryParams = QueryParams(queryParams)))

  def lookup: Either[String, GraphDbHttp.Lookup] = GraphDbHttp.lookup(toRequest)

private[nullptr7] object GraphDbHttpSpecSupport:
  val namePredicate = "http://schema.org/name"
  val typePredicate = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
  val personType    = "http://schema.org/Person"
  val alice         = "http://example.org/person/1"
  val teamAGraph    = "http://example.org/graphs/team-a"
