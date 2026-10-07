package com.github.nullptr7

import org.eclipse.rdf4j.model.{IRI, Resource, Value}
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

trait GraphDb:
  def addIri(subject:            String, predicate: String, obj:   String): IO[GraphDbError, Unit]
  def addLiteral(subject:        String, predicate: String, value: String): IO[GraphDbError, Unit]
  def removeIri(
      subject:   String,
      predicate: String,
      obj:       Option[String] = None
  ): IO[GraphDbError, Unit]
  def replaceLiteral(
      subject:   String,
      predicate: String,
      oldValue:  String,
      newValue:  String
  ): IO[GraphDbError, Unit]
  def allTriples: IO[GraphDbError, List[(String, String, String)]]
  def subjectsWithIri(predicate: String, obj:       String): IO[GraphDbError, List[String]]
  def objectsFor(subject:        String, predicate: String): IO[GraphDbError, List[String]]
  def select(query:              String): IO[GraphDbError, List[Map[String, String]]]

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
    private def iri(value: String): IRI = values.createIRI(value)

    private def withConnection[A](
        operation: RepositoryConnection => ZIO[Scope, GraphDbError, A]
    ): IO[GraphDbError, A] =
      ZIO.scoped(autoCloseable(repository.getConnection).flatMap(operation))

    private def triples(
        subject:   Resource | Null,
        predicate: IRI | Null,
        obj:       Value | Null
    ): IO[GraphDbError, List[(String, String, String)]] =
      withConnection { connection =>
        autoCloseable(connection.getStatements(subject, predicate, obj, true)).flatMap {
          statements =>
            attempt {
              statements.asScala.map { statement =>
                (
                  statement.getSubject.stringValue,
                  statement.getPredicate.stringValue,
                  statement.getObject.toString
                )
              }.toList
            }
        }
      }

    def addIri(subject: String, predicate: String, obj: String): IO[GraphDbError, Unit] =
      withConnection(connection => attempt(connection.add(iri(subject), iri(predicate), iri(obj))))

    def addLiteral(subject: String, predicate: String, value: String): IO[GraphDbError, Unit] =
      withConnection(connection =>
        attempt(connection.add(iri(subject), iri(predicate), values.createLiteral(value)))
      )

    def removeIri(subject: String, predicate: String, obj: Option[String]): IO[GraphDbError, Unit] =
      withConnection(connection =>
        attempt(connection.remove(iri(subject), iri(predicate), obj.map(iri).orNull))
      )

    def replaceLiteral(
        subject:   String,
        predicate: String,
        oldValue:  String,
        newValue:  String
    ): IO[GraphDbError, Unit] =
      withConnection { connection =>
        attempt {
          connection.begin()
          try
            connection.remove(iri(subject), iri(predicate), values.createLiteral(oldValue))
            connection.add(iri(subject), iri(predicate), values.createLiteral(newValue))
            connection.commit()
          catch
            case error: Throwable =>
              connection.rollback()
              throw error
        }
      }

    def allTriples: IO[GraphDbError, List[(String, String, String)]] = triples(null, null, null)

    def subjectsWithIri(predicate: String, obj: String): IO[GraphDbError, List[String]] =
      triples(null, iri(predicate), iri(obj)).map(_.map(_._1))

    def objectsFor(subject: String, predicate: String): IO[GraphDbError, List[String]] =
      triples(iri(subject), iri(predicate), null).map(_.map(_._3))

    def select(query: String): IO[GraphDbError, List[Map[String, String]]] =
      withConnection { connection =>
        autoCloseable(connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate())
          .flatMap { results =>
            attempt {
              results.asScala.map { bindings =>
                bindings.getBindingNames.asScala.iterator.map { name =>
                  name -> bindings.getValue(name).toString
                }.toMap
              }.toList
            }
          }
      }

    private[GraphDb] def close: IO[GraphDbError, Unit] = attempt(manager.shutDown())
