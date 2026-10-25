package com.github.nullptr7

import zio.*
import zio.http.*
import zio.json.*

import java.net.URI
import scala.util.Try
import Rdf.*

object GraphDbHttp:

  private[nullptr7] type ErrorStringOr[A] = Either[String, A]

  private[nullptr7] enum Lookup:
    case Objects(subject: Subject, predicate: Predicate, graph: Option[Graph])
    case Subjects(predicate: Predicate, obj: ObjectFilter, graph: Option[Graph])

  private def isAbsoluteIri(value: String): Boolean =
    Try(URI.create(value)).toOption.exists(_.isAbsolute)

  private def iri(name: String, value: String): ErrorStringOr[String] =
    Either.cond(isAbsoluteIri(value), value, s"$name must be an absolute IRI")

  private def predicate(request: Request): Either[String, Predicate] =
    for
      value <- request.queryParam("predicate").toRight("predicate is required")
      valid <- iri("predicate", value)
    yield Predicate(valid)

  private def graph(request: Request): ErrorStringOr[Option[Graph]] =
    request.queryParam("graph") match
      case Some(value) => iri("graph", value).map(valid => Some(Graph(valid)))
      case None        => Right(None)

  private def objectFilter(
      value:      String,
      objectType: Option[String]
  ): ErrorStringOr[ObjectFilter] =
    objectType match
      case Some("iri")                  =>
        iri("object", value).map(valid => ObjectFilter.Iri(IriObject(valid)))
      case Some("literal")              => Right(ObjectFilter.Literal(Literal(value)))
      case None if isAbsoluteIri(value) => Right(ObjectFilter.Iri(IriObject(value)))
      case None                         => Right(ObjectFilter.Literal(Literal(value)))
      case Some(_)                      => Left("objectType must be iri or literal")

  private def lookupTerm(
      request:   Request,
      predicate: Predicate,
      graph:     Option[Graph]
  ): ErrorStringOr[Lookup] =
    (request.queryParam("subject"), request.queryParam("object")) match
      case (Some(value), None) =>
        iri("subject", value).map(valid => Lookup.Objects(Subject(valid), predicate, graph))
      case (None, Some(value)) =>
        objectFilter(value, request.queryParam("objectType"))
          .map(obj => Lookup.Subjects(predicate, obj, graph))
      case _ => Left("provide exactly one of subject or object, together with predicate")

  private[nullptr7] def lookup(request: Request): ErrorStringOr[Lookup] =
    for
      validPredicate <- predicate(request)
      selectedGraph  <- graph(request)
      result         <- lookupTerm(request, validPredicate, selectedGraph)
    yield result

  private def query(request: Request): URIO[GraphDb, Response] =
    lookup(request) match
      case Left(message) => ZIO.succeed(Response.badRequest(message))
      case Right(Lookup.Objects(subject, predicate, graph)) =>
        ZIO
          .serviceWithZIO[GraphDb](_.objectsFor(subject, predicate, graph))
          .fold(
            error  => Response.internalServerError(error.getMessage),
            values => Response.json(values.map(_.rendered).toJson)
          )
      case Right(Lookup.Subjects(predicate, obj, graph))    =>
        ZIO
          .serviceWithZIO[GraphDb](_.subjectsFor(predicate, obj, graph))
          .fold(
            error    => Response.internalServerError(error.getMessage),
            subjects => Response.json(subjects.map(_.asString).toJson)
          )

  private val routes = Routes(
    Method.GET / "query" -> handler { (request: Request) => query(request) }
  )

  val server: RLayer[HttpSettings, Server] =
    ZLayer.fromZIO(
      ZIO.serviceWith[HttpSettings](settings =>
        Server.Config.default.binding("127.0.0.1", settings.port)
      )
    ) >>> Server.live

  def serve: URIO[GraphDb & Server, Nothing] = Server.serve(routes)
