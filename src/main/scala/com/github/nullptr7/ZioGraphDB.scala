package com.github.nullptr7

import org.eclipse.rdf4j.model.{IRI, Resource, Value as Rdf4jValue}
import org.eclipse.rdf4j.model.impl.{SimpleValueFactory, TreeModel}
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.repository.{Repository, RepositoryConnection}
import org.eclipse.rdf4j.repository.config.RepositoryConfig
import org.eclipse.rdf4j.repository.manager.LocalRepositoryManager
import org.eclipse.rdf4j.rio.{RDFFormat, Rio}
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import zio.*

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

final case class GraphDbError(cause: Throwable) extends Exception(cause)

object Rdf:
  opaque type Subject   = String
  opaque type Predicate = String
  opaque type IriObject = String
  opaque type Literal   = String
  opaque type Value     = String
  opaque type Query     = String

  object Subject:
    def apply(value: String): Subject = value

  object Predicate:
    def apply(value: String): Predicate = value

  object IriObject:
    def apply(value: String): IriObject = value

  object Literal:
    def apply(value: String): Literal = value

  object Value:
    private[nullptr7] def apply(value: String): Value = value

  object Query:
    def apply(value: String): Query = value

  extension (value: Subject | Predicate | IriObject | Literal | Query)
    private[nullptr7] def asString: String = value

  extension (value: Value) def rendered: String = value

final case class Triple(subject: Rdf.Subject, predicate: Rdf.Predicate, obj: Rdf.Value)

trait GraphDb:
  def addIri(
      subject:   Rdf.Subject,
      predicate: Rdf.Predicate,
      obj:       Rdf.IriObject
  ): IO[GraphDbError, Unit]
  def addLiteral(
      subject:   Rdf.Subject,
      predicate: Rdf.Predicate,
      value:     Rdf.Literal
  ): IO[GraphDbError, Unit]
  def removeIri(
      subject:   Rdf.Subject,
      predicate: Rdf.Predicate,
      obj:       Option[Rdf.IriObject] = None
  ): IO[GraphDbError, Unit]
  def replaceLiteral(
      subject:   Rdf.Subject,
      predicate: Rdf.Predicate,
      oldValue:  Rdf.Literal,
      newValue:  Rdf.Literal
  ): IO[GraphDbError, Unit]
  def allTriples: IO[GraphDbError, List[Triple]]
  def subjectsWithIri(
      predicate: Rdf.Predicate,
      obj:       Rdf.IriObject
  ): IO[GraphDbError, List[Rdf.Subject]]
  def objectsFor(subject: Rdf.Subject, predicate: Rdf.Predicate): IO[GraphDbError, List[Rdf.Value]]
  def select(query:       Rdf.Query): IO[GraphDbError, List[Map[String, Rdf.Value]]]

object GraphDb:
  private val repositoryId                    = "graphdb-embedded"
  private val values                          = SimpleValueFactory.getInstance()
  private val legacyRepositoryConfigNamespace = "http://www.openrdf.org/config/repository#"
  private val repositoryConfigType = values.createIRI(legacyRepositoryConfigNamespace, "Repository")

  def scoped(baseDirectory: Path): ZIO[Scope, GraphDbError, GraphDb] =
    for
      configuration <- loadConfiguration
      database      <- ZIO.acquireRelease(open(baseDirectory, configuration))(_.close.orDie)
    yield database

  private def attempt[A](operation: => A): IO[GraphDbError, A] =
    ZIO.attemptBlocking(operation).mapError(GraphDbError.apply)

  private def autoCloseable[A <: AutoCloseable](acquire: => A): ZIO[Scope, GraphDbError, A] =
    ZIO.fromAutoCloseable(ZIO.attemptBlocking(acquire)).mapError(GraphDbError.apply)

  private def open(baseDirectory: Path, configuration: RepositoryConfig): IO[GraphDbError, Live] =
    attempt {
      val manager = new LocalRepositoryManager(baseDirectory.toFile)
      try
        manager.init()
        manager.addRepositoryConfig(configuration)
        new Live(manager.getRepository(repositoryId), manager)
      catch
        case error: Throwable =>
          manager.shutDown()
          throw error
    }

  private def loadConfiguration: ZIO[Scope, GraphDbError, RepositoryConfig] =
    for
      input         <- autoCloseable {
        Option(getClass.getResourceAsStream("/graphdb-config.ttl")).getOrElse {
          throw new IllegalStateException("graphdb-config.ttl is missing from the classpath")
        }
      }
      configuration <- attempt {
        val graph   = new TreeModel()
        val parser  = Rio.createParser(RDFFormat.TURTLE)
        parser.setRDFHandler(new StatementCollector(graph))
        parser.parse(input, legacyRepositoryConfigNamespace)
        val subject = Models
          .subject(graph.filter(null, RDF.TYPE, repositoryConfigType))
          .orElseThrow(() =>
            new IllegalStateException("Repository configuration has no repository subject")
          )
        RepositoryConfig.create(graph, subject)
      }
    yield configuration

  private final class Live(repository: Repository, manager: LocalRepositoryManager) extends GraphDb:
    private def iri(value: Rdf.Subject | Rdf.Predicate | Rdf.IriObject): IRI =
      values.createIRI(value.asString)

    private def withConnection[A](
        operation: RepositoryConnection => ZIO[Scope, GraphDbError, A]
    ): IO[GraphDbError, A] =
      ZIO.scoped(autoCloseable(repository.getConnection).flatMap(operation))

    private def triples(
        subject:   Resource | Null,
        predicate: IRI | Null,
        obj:       Rdf4jValue | Null
    ): IO[GraphDbError, List[Triple]] =
      withConnection { connection =>
        autoCloseable(connection.getStatements(subject, predicate, obj, true)).flatMap {
          statements =>
            attempt {
              statements.asScala
                .map(statement =>
                  Triple(
                    Rdf.Subject(statement.getSubject.stringValue),
                    Rdf.Predicate(statement.getPredicate.stringValue),
                    Rdf.Value(statement.getObject.toString)
                  )
                )
                .toList
            }
        }
      }

    def addIri(
        subject:   Rdf.Subject,
        predicate: Rdf.Predicate,
        obj:       Rdf.IriObject
    ): IO[GraphDbError, Unit] =
      withConnection(connection => attempt(connection.add(iri(subject), iri(predicate), iri(obj))))

    def addLiteral(
        subject:   Rdf.Subject,
        predicate: Rdf.Predicate,
        value:     Rdf.Literal
    ): IO[GraphDbError, Unit] =
      withConnection(connection =>
        attempt(connection.add(iri(subject), iri(predicate), values.createLiteral(value.asString)))
      )

    def removeIri(
        subject:   Rdf.Subject,
        predicate: Rdf.Predicate,
        obj:       Option[Rdf.IriObject]
    ): IO[GraphDbError, Unit] =
      withConnection(connection =>
        attempt(connection.remove(iri(subject), iri(predicate), obj.map(iri).orNull))
      )

    def replaceLiteral(
        subject:   Rdf.Subject,
        predicate: Rdf.Predicate,
        oldValue:  Rdf.Literal,
        newValue:  Rdf.Literal
    ): IO[GraphDbError, Unit] =
      withConnection { connection =>
        attempt {
          connection.begin()
          try
            connection.remove(iri(subject), iri(predicate), values.createLiteral(oldValue.asString))
            connection.add(iri(subject), iri(predicate), values.createLiteral(newValue.asString))
            connection.commit()
          catch
            case error: Throwable =>
              connection.rollback()
              throw error
        }
      }

    def allTriples: IO[GraphDbError, List[Triple]] = triples(null, null, null)

    def subjectsWithIri(
        predicate: Rdf.Predicate,
        obj:       Rdf.IriObject
    ): IO[GraphDbError, List[Rdf.Subject]] =
      triples(null, iri(predicate), iri(obj)).map(_.map(_.subject))

    def objectsFor(
        subject:   Rdf.Subject,
        predicate: Rdf.Predicate
    ): IO[GraphDbError, List[Rdf.Value]] =
      triples(iri(subject), iri(predicate), null).map(_.map(_.obj))

    def select(query: Rdf.Query): IO[GraphDbError, List[Map[String, Rdf.Value]]] =
      withConnection { connection =>
        autoCloseable(connection.prepareTupleQuery(QueryLanguage.SPARQL, query.asString).evaluate())
          .flatMap { results =>
            attempt {
              results.asScala.map { bindings =>
                bindings.getBindingNames.asScala.iterator.map { name =>
                  name -> Rdf.Value(bindings.getValue(name).toString)
                }.toMap
              }.toList
            }
          }
      }

    private[GraphDb] def close: IO[GraphDbError, Unit] = attempt(manager.shutDown())
