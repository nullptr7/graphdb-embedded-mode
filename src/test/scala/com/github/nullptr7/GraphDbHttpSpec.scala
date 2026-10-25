package com.github.nullptr7

import zio.test.*
import zio.Scope
import Rdf.*
import GraphDbHttpSpecSupport.*

object GraphDbHttpSpec extends ZIOSpecDefault:
  private def accepts(input: QueryInput, expected: GraphDbHttp.Lookup) =
    assertTrue(input.lookup == Right(expected))

  private def rejects(input: QueryInput, expectedMessage: String) =
    assertTrue(input.lookup == Left(expectedMessage))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GraphDbHttp lookup")(
      test("subject and predicate selects objects") {
        accepts(
          QueryInput(subject = Some(alice), predicate = Some(namePredicate)),
          GraphDbHttp.Lookup.Objects(Subject(alice), Predicate(namePredicate), None)
        )
      },
      test("object IRI and predicate selects subjects") {
        accepts(
          QueryInput(obj = Some(personType), predicate = Some(typePredicate)),
          GraphDbHttp.Lookup.Subjects(
            Predicate(typePredicate),
            ObjectFilter.Iri(IriObject(personType)),
            None
          )
        )
      },
      test("literal object and named graph are preserved") {
        accepts(
          QueryInput(
            predicate = Some(namePredicate),
            obj = Some("Alice"),
            objectType = Some("literal"),
            graph = Some(teamAGraph)
          ),
          GraphDbHttp.Lookup.Subjects(
            Predicate(namePredicate),
            ObjectFilter.Literal(Literal("Alice")),
            Some(Graph(teamAGraph))
          )
        )
      },
      test("missing predicate is rejected") {
        rejects(QueryInput(subject = Some(alice)), "predicate is required")
      },
      test("both subject and object are rejected") {
        rejects(
          QueryInput(
            subject = Some(alice),
            predicate = Some(namePredicate),
            obj = Some("Alice")
          ),
          "provide exactly one of subject or object, together with predicate"
        )
      },
      test("invalid subject IRI is rejected") {
        rejects(
          QueryInput(subject = Some("not-an-iri"), predicate = Some(namePredicate)),
          "subject must be an absolute IRI"
        )
      },
      test("unknown object type is rejected") {
        rejects(
          QueryInput(
            predicate = Some(namePredicate),
            obj = Some("Alice"),
            objectType = Some("unknown")
          ),
          "objectType must be iri or literal"
        )
      }
    )
